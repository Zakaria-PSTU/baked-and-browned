package com.bakedandbrowned.notification.sms.sslwireless;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.bakedandbrowned.notification.sms.SmsSender;
import com.bakedandbrowned.notification.sms.sslwireless.SslWirelessProperties.Settings;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SSL Wireless ISMS Plus (ADR-0027), built from the public API documentation
 * (ismsplus.sslwireless.com/api-documentation); to be confirmed with SSL Wireless before going live.
 * <ul>
 * <li>{@code POST {base-url}/send-sms}, JSON: {@code api_token}, {@code sid}, {@code msisdn} (our
 * {@code +8801XXXXXXXXX} without the {@code +}), {@code sms}, {@code csms_id}.</li>
 * <li>{@code csms_id} is the first 20 hex digits of the notice id: the same on every resend, so a
 * message that already got through comes back as a duplicate, which counts as sent.</li>
 * <li>Outcomes: accepted or duplicate → the provider's {@code reference_id} (or our {@code csms_id});
 * a permanent rejection → {@link SmsRejectedException}; a wrong configuration, a temporary failure,
 * an unreadable response or an unknown code → {@link SmsDeliveryException}, retried.</li>
 * </ul>
 * The token goes only to SSL Wireless over https. Message text, full numbers and the token are
 * never logged; neither are response bodies.
 */
public class SslWirelessSmsSender implements SmsSender {

	public static final String PROVIDER = "ssl-wireless";

	static final String SEND_PATH = "/send-sms";

	/** The documented maximum length of {@code csms_id}. */
	static final int CSMS_ID_LENGTH = 20;

	/** Never acceptable for this message: give up (ADR-0027). */
	static final Set<String> PERMANENT = Set.of("4020", "4024", "4025", "4026", "4027", "4028", "4032");

	/** Our own setup is wrong: retried, logged at ERROR, so fixing it loses no notice. */
	static final Set<String> CONFIGURATION = Set.of("4001", "4002", "4003", "4004", "4005", "4022", "4035");

	static final String DUPLICATE_CODE = "4023";

	/** Per-recipient {@code sms_status} values that mean this number will not take the message. */
	static final Set<String> REFUSED_RECIPIENT = Set.of("INVALID", "BLOCKED");

	private static final Pattern BD_MOBILE = Pattern.compile("\\+8801\\d{9}");

	private static final Logger log = LoggerFactory.getLogger(SslWirelessSmsSender.class);

	private final Settings settings;

	private final RestClient http;

	private final JsonMapper json;

	public SslWirelessSmsSender(SslWirelessProperties properties, JsonMapper json) {
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
	public String send(SmsMessage message) throws SmsDeliveryException {
		if (!BD_MOBILE.matcher(message.to()).matches()) {
			// Every account phone is validated as +8801XXXXXXXXX; anything else can never be sent.
			throw new SmsRejectedException("INVALID_NUMBER", "Not a Bangladeshi mobile number: " + message);
		}
		String csmsId = csmsId(message.idempotencyKey());
		Map<String, String> body = new LinkedHashMap<>();
		body.put("api_token", settings.apiToken());
		body.put("sid", settings.senderId());
		body.put("msisdn", message.to().substring(1));
		body.put("sms", message.text());
		body.put("csms_id", csmsId);
		JsonNode response = parse(post(body));
		String code = text(response, "status_code");
		JsonNode info = response.path("smsinfo").path(0);
		String smsStatus = text(info, "sms_status");
		if ("200".equals(code) && "SUCCESS".equals(smsStatus)) {
			String reference = text(info, "reference_id");
			return reference == null || reference.isBlank() ? csmsId : reference;
		}
		if (DUPLICATE_CODE.equals(code) || "DUPLICATE".equals(smsStatus)) {
			// Our earlier attempt got through (a resend after a crash or a lost response).
			log.info("SSL Wireless reports csms_id {} for {} as a duplicate: already sent", csmsId, message);
			return csmsId;
		}
		if (smsStatus != null && REFUSED_RECIPIENT.contains(smsStatus)) {
			// The request was fine, this recipient is not.
			throw new SmsRejectedException(smsStatus, "SSL Wireless refused " + message + ": " + smsStatus);
		}
		if (code != null && PERMANENT.contains(code)) {
			throw new SmsRejectedException(code, "SSL Wireless rejected " + message + " with code " + code);
		}
		if (code != null && CONFIGURATION.contains(code)) {
			log.error("SSL Wireless refused the request with code {}: check the API token, sender ID and IP whitelist",
					code);
			throw new SmsDeliveryException("SSL Wireless configuration error, code " + code);
		}
		throw new SmsDeliveryException("SSL Wireless did not accept " + message + ", code " + code);
	}

	/** The first 20 hex digits of the notice id: deterministic, so every resend carries the same one. */
	static String csmsId(UUID idempotencyKey) {
		String hex = HexFormat.of().toHexDigits(idempotencyKey.getMostSignificantBits())
				+ HexFormat.of().toHexDigits(idempotencyKey.getLeastSignificantBits());
		return hex.substring(0, CSMS_ID_LENGTH);
	}

	private String post(Map<String, String> body) throws SmsDeliveryException {
		try {
			// Errors are reported in the JSON body, possibly with a non-2xx status: read it either way.
			return http.post()
				.uri(settings.baseUrl() + SEND_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(json.writeValueAsString(body))
				.exchange((request, response) -> new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (RestClientException ex) {
			// Timeouts and connection failures: ambiguous, so retried with the same csms_id.
			throw new SmsDeliveryException("SSL Wireless could not be reached: " + ex.getClass().getSimpleName());
		}
	}

	private JsonNode parse(@Nullable String body) throws SmsDeliveryException {
		if (body == null || body.isBlank()) {
			throw new SmsDeliveryException("SSL Wireless returned an empty response");
		}
		try {
			JsonNode node = json.readTree(body);
			if (!node.isObject()) {
				throw new SmsDeliveryException("SSL Wireless returned an unexpected response");
			}
			return node;
		}
		catch (JacksonException ex) {
			throw new SmsDeliveryException("SSL Wireless returned a response that is not JSON");
		}
	}

	private static @Nullable String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		// status_code is a JSON number in the docs; accept it as a string too.
		return value.isString() ? value.asString() : value.toString();
	}

}
