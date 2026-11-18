package com.bakedandbrowned.payment.gateway.sslcommerz;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.bakedandbrowned.common.value.InvalidValueException;
import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.HostedPaymentSession;
import com.bakedandbrowned.payment.gateway.PaymentGateway;
import com.bakedandbrowned.payment.gateway.PaymentSessionRequest;
import com.bakedandbrowned.payment.gateway.PaymentVerificationException;
import com.bakedandbrowned.payment.gateway.ProviderCallback;
import com.bakedandbrowned.payment.gateway.VerifiedPaymentResult;
import com.bakedandbrowned.payment.gateway.sslcommerz.SslCommerzProperties.Settings;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SSLCommerz v4 hosted checkout (ADR-0012), built from the public developer docs
 * (developer.sslcommerz.com/doc/v4); to be confirmed against the sandbox before going live.
 * <ul>
 * <li><b>Session</b>: {@code POST /gwprocess/v4/api.php}, form-encoded; the customer goes to
 * {@code GatewayPageURL}. Our session id is the {@code tran_id} we send: {@code BB} plus the payment
 * id in base 36 (at most 27 characters; the docs allow 30), so it is stable per payment.</li>
 * <li><b>IPN</b> (the webhook): a form POST. Nothing in it is trusted. The docs do not specify how
 * {@code verify_sign} is computed, so authenticity rests on the server-to-server calls: a
 * {@code VALID}/{@code VALIDATED} notice is resolved with the Order Validation API by its
 * {@code val_id} (a forged id is rejected there, and the validated {@code tran_id} must be the
 * notice's); any other notice is resolved with the Transaction Query API by {@code tran_id}.</li>
 * <li><b>Status</b>: {@code GET /validator/api/merchantTransIDvalidationAPI.php?tran_id=}; a
 * {@code VALID}/{@code VALIDATED} attempt wins, then {@code PENDING}/{@code UNATTEMPTED}, then
 * {@code CANCELLED}/{@code EXPIRED} as CANCELLED, anything else as FAILED.</li>
 * <li>Amounts are compared in the currency we asked for: {@code currency_type}/{@code currency_amount}
 * when present (the docs warn {@code amount} is converted to BDT for foreign currencies), otherwise
 * {@code currency}/{@code amount}. The order number travels in {@code value_a} and comes back as
 * the order reference. {@code risk_level=1} marks the result high risk.</li>
 * </ul>
 * The store password goes only to SSLCommerz over https and is never logged; neither are payloads.
 */
public class SslCommerzGateway implements PaymentGateway {

	public static final String PROVIDER = "sslcommerz";

	private static final String SESSION_PATH = "/gwprocess/v4/api.php";

	private static final String VALIDATION_PATH = "/validator/api/validationserverAPI.php";

	private static final String QUERY_PATH = "/validator/api/merchantTransIDvalidationAPI.php";

	private final Settings settings;

	private final RestClient http;

	private final JsonMapper json;

	public SslCommerzGateway(SslCommerzProperties properties, JsonMapper json) {
		this.settings = properties.settings();
		this.json = json;
		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(settings.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
		requests.setReadTimeout(settings.readTimeout());
		this.http = RestClient.builder().requestFactory(requests).build();
	}

	@Override
	public String provider() {
		return PROVIDER;
	}

	@Override
	public HostedPaymentSession createSession(PaymentSessionRequest request) {
		String tranId = tranId(request.paymentId());
		PaymentSessionRequest.PaymentCustomer customer = request.customer();
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("store_id", settings.storeId());
		form.add("store_passwd", settings.storePassword());
		form.add("total_amount", request.amount().amount().toPlainString());
		form.add("currency", "BDT");
		form.add("tran_id", tranId);
		form.add("success_url", request.returnUrl().toString());
		form.add("fail_url", request.returnUrl().toString());
		form.add("cancel_url", request.returnUrl().toString());
		form.add("ipn_url", settings.ipnUrl().toString());
		form.add("cus_name", customer.name());
		form.add("cus_email", customer.email() == null ? settings.fallbackCustomerEmail() : customer.email());
		form.add("cus_add1", customer.address());
		form.add("cus_add2", customer.area());
		form.add("cus_city", settings.customerCity());
		form.add("cus_postcode", settings.customerPostcode());
		form.add("cus_country", settings.customerCountry());
		form.add("cus_phone", customer.phone());
		// Delivered by the bakery itself, not SSLCommerz logistics.
		form.add("shipping_method", "NO");
		form.add("num_of_item", "1");
		form.add("product_name", "Order " + request.orderReference());
		form.add("product_category", "Bakery");
		form.add("product_profile", "physical-goods");
		form.add("value_a", request.orderReference());
		JsonNode response = post(SESSION_PATH, form);
		if (!"SUCCESS".equals(text(response, "status"))) {
			// failedreason is a provider message (for example a credential error), never customer data.
			throw new PaymentVerificationException("SSLCommerz refused the session: " + text(response, "failedreason"));
		}
		String page = text(response, "GatewayPageURL");
		URI checkout = page == null ? null : URI.create(page);
		if (checkout == null || !checkout.isAbsolute()) {
			throw new PaymentVerificationException("SSLCommerz returned no checkout page");
		}
		return new HostedPaymentSession(tranId, checkout);
	}

	@Override
	public VerifiedPaymentResult verify(ProviderCallback callback) {
		Map<String, String> notice;
		try {
			notice = form(callback.body());
		}
		catch (IllegalArgumentException ex) {
			throw new PaymentVerificationException("SSLCommerz notice is not form-encoded");
		}
		String tranId = notice.get("tran_id");
		if (tranId == null || tranId.isBlank()) {
			throw new PaymentVerificationException("SSLCommerz notice has no tran_id");
		}
		String claimed = notice.getOrDefault("status", "");
		String valId = notice.get("val_id");
		if (("VALID".equals(claimed) || "VALIDATED".equals(claimed)) && valId != null && !valId.isBlank()) {
			return validated(tranId, valId);
		}
		// Failures, cancellations and anything unexpected: ask the provider, never take the notice's word.
		return queryStatus(tranId);
	}

	@Override
	public VerifiedPaymentResult queryStatus(String sessionId) {
		JsonNode response = get(QUERY_PATH, Map.of("tran_id", sessionId));
		if (!"DONE".equals(text(response, "APIConnect"))) {
			throw new PaymentVerificationException("SSLCommerz transaction query failed: " + text(response, "APIConnect"));
		}
		JsonNode elements = response.get("element");
		if (elements == null || !elements.isArray() || elements.isEmpty()) {
			throw new PaymentVerificationException("SSLCommerz does not know this transaction");
		}
		JsonNode best = null;
		GatewayPaymentStatus bestStatus = null;
		for (JsonNode element : elements) {
			if (!sessionId.equals(text(element, "tran_id"))) {
				continue;
			}
			GatewayPaymentStatus status = status(text(element, "status"));
			if (bestStatus == null || rank(status) > rank(bestStatus)) {
				best = element;
				bestStatus = status;
			}
		}
		if (best == null || bestStatus == null) {
			throw new PaymentVerificationException("SSLCommerz returned no attempt for this transaction");
		}
		return result(sessionId, best, bestStatus);
	}

	private VerifiedPaymentResult validated(String tranId, String valId) {
		JsonNode response = get(VALIDATION_PATH, Map.of("val_id", valId));
		String status = text(response, "status");
		if (!"VALID".equals(status) && !"VALIDATED".equals(status)) {
			throw new PaymentVerificationException("SSLCommerz did not validate the notice: " + status);
		}
		if (!tranId.equals(text(response, "tran_id"))) {
			throw new PaymentVerificationException("SSLCommerz validated a different transaction");
		}
		return result(tranId, response, GatewayPaymentStatus.SUCCEEDED);
	}

	private VerifiedPaymentResult result(String tranId, JsonNode node, GatewayPaymentStatus status) {
		String currency = firstPresent(text(node, "currency_type"), text(node, "currency"));
		String amount = text(node, "currency_type") != null ? text(node, "currency_amount") : text(node, "amount");
		Money money;
		try {
			money = amount == null ? Money.ZERO : Money.of(new BigDecimal(amount.strip()));
		}
		catch (NumberFormatException | InvalidValueException ex) {
			throw new PaymentVerificationException("SSLCommerz reported an unreadable amount");
		}
		String valId = text(node, "val_id");
		String transactionId = null;
		String eventId = tranId + ":" + status.name();
		if (status == GatewayPaymentStatus.SUCCEEDED) {
			if (valId == null || valId.isBlank()) {
				throw new PaymentVerificationException("SSLCommerz reported a success without val_id");
			}
			String bank = text(node, "bank_tran_id");
			transactionId = bank == null || bank.isBlank() ? valId : bank;
			eventId = eventId + ":" + valId;
		}
		String reference = text(node, "value_a");
		return new VerifiedPaymentResult(PROVIDER, eventId, tranId, transactionId, reference == null ? "" : reference,
				money, currency == null ? "" : currency.strip(), status, "1".equals(text(node, "risk_level")));
	}

	/** Stable, at most 27 characters of [0-9a-z]: SSLCommerz allows 30 and does not document a character set. */
	static String tranId(UUID paymentId) {
		ByteBuffer bytes = ByteBuffer.allocate(16);
		bytes.putLong(paymentId.getMostSignificantBits()).putLong(paymentId.getLeastSignificantBits());
		return "BB" + new BigInteger(1, bytes.array()).toString(36);
	}

	static GatewayPaymentStatus status(@Nullable String status) {
		return switch (status == null ? "" : status) {
			case "VALID", "VALIDATED" -> GatewayPaymentStatus.SUCCEEDED;
			case "PENDING", "UNATTEMPTED" -> GatewayPaymentStatus.PENDING;
			case "CANCELLED", "EXPIRED" -> GatewayPaymentStatus.CANCELLED;
			default -> GatewayPaymentStatus.FAILED;
		};
	}

	/** Which attempt describes a transaction best when it has several: any success wins. */
	private static int rank(GatewayPaymentStatus status) {
		return switch (status) {
			case SUCCEEDED -> 3;
			case PENDING -> 2;
			case CANCELLED -> 1;
			case FAILED -> 0;
		};
	}

	private JsonNode post(String path, MultiValueMap<String, String> form) {
		try {
			String body = http.post()
				.uri(settings.baseUrl() + path)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.accept(MediaType.APPLICATION_JSON)
				.body(form)
				.retrieve()
				.body(String.class);
			return parse(body);
		}
		catch (RestClientException ex) {
			throw new PaymentVerificationException("SSLCommerz could not be reached: " + ex.getClass().getSimpleName());
		}
	}

	private JsonNode get(String path, Map<String, String> query) {
		UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(settings.baseUrl() + path);
		query.forEach(uri::queryParam);
		uri.queryParam("store_id", settings.storeId())
			.queryParam("store_passwd", settings.storePassword())
			.queryParam("format", "json");
		try {
			String body = http.get()
				.uri(uri.encode().build().toUri())
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class);
			return parse(body);
		}
		catch (RestClientException ex) {
			// The request URI carries the store password: report the failure type only.
			throw new PaymentVerificationException("SSLCommerz could not be reached: " + ex.getClass().getSimpleName());
		}
	}

	private JsonNode parse(@Nullable String body) {
		if (body == null || body.isBlank()) {
			throw new PaymentVerificationException("SSLCommerz returned an empty response");
		}
		try {
			JsonNode node = json.readTree(body);
			if (!node.isObject()) {
				throw new PaymentVerificationException("SSLCommerz returned an unexpected response");
			}
			return node;
		}
		catch (JacksonException ex) {
			throw new PaymentVerificationException("SSLCommerz returned a response that is not JSON");
		}
	}

	private static @Nullable String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		return value.isString() ? value.asString() : value.toString();
	}

	private static @Nullable String firstPresent(@Nullable String first, @Nullable String second) {
		return first != null && !first.isBlank() ? first : second;
	}

	/** Parses an {@code application/x-www-form-urlencoded} body; the first value of a repeated key wins. */
	static Map<String, String> form(String body) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (String pair : body.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			int eq = pair.indexOf('=');
			String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			fields.putIfAbsent(key, value);
		}
		return fields;
	}

}
