package com.bakedandbrowned.payment.gateway.sslcommerz;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.ProviderCallback;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for the SSLCommerz v4 endpoints the adapter uses, answering with the fields and
 * status values of the public docs (developer.sslcommerz.com/doc/v4): session initiation, the Order
 * Validation API and the Transaction Query API by {@code tran_id}. Responses the docs leave open
 * (for example a transaction that was never attempted) are this stub's assumptions, to be checked
 * against the sandbox.
 */
final class SslCommerzStub implements AutoCloseable {

	static final String STORE_ID = "bakedtest01";

	static final String STORE_PASSWORD = "stub-secret@ssl";

	private final HttpServer server;

	private final JsonMapper json = JsonMapper.builder().build();

	/** By tran_id. */
	private final Map<String, Txn> transactions = new ConcurrentHashMap<>();

	/** Every request's decoded parameters, for assertions on what the adapter sent. */
	final List<Map<String, String>> sessionRequests = new CopyOnWriteArrayList<>();

	final List<URI> requestUris = new CopyOnWriteArrayList<>();

	SslCommerzStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/gwprocess/v4/api.php", this::session);
		server.createContext("/validator/api/validationserverAPI.php", this::validation);
		server.createContext("/validator/api/merchantTransIDvalidationAPI.php", this::query);
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	SslCommerzProperties properties() {
		return properties(baseUrl());
	}

	static SslCommerzProperties properties(String baseUrl) {
		return new SslCommerzProperties(true, URI.create(baseUrl), STORE_ID, STORE_PASSWORD,
				URI.create("https://api.example.test/api/v1/payments/webhooks/sslcommerz"), "orders@bakedandbrowned.test",
				"Dhaka", "1000", "Bangladesh", Duration.ofSeconds(2), Duration.ofSeconds(2));
	}

	// ------------------------------------------------------------------ provider-side simulation

	/** The customer finishes on the hosted page. */
	void settle(String tranId, GatewayPaymentStatus outcome) {
		transactions.computeIfPresent(tranId, (id, txn) -> txn.with(switch (outcome) {
			case SUCCEEDED -> "VALID";
			case FAILED -> "FAILED";
			case CANCELLED -> "CANCELLED";
			case PENDING -> "PENDING";
		}));
	}

	/** The provider records a different charge or currency (tampered or misrouted payment). */
	void charge(String tranId, String amount, String currency) {
		transactions.computeIfPresent(tranId, (id, txn) -> new Txn(txn.tranId, amount, currency, txn.reference,
				txn.status, txn.valId, txn.validated, txn.riskLevel));
	}

	void flagHighRisk(String tranId) {
		transactions.computeIfPresent(tranId, (id, txn) -> new Txn(txn.tranId, txn.amount, txn.currency, txn.reference,
				txn.status, txn.valId, txn.validated, "1"));
	}

	/** The IPN SSLCommerz would POST for the transaction, claiming {@code claimed}. */
	ProviderCallback ipn(String tranId, GatewayPaymentStatus claimed) {
		Txn txn = transactions.get(tranId);
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("status", switch (claimed) {
			case SUCCEEDED -> "VALID";
			case FAILED -> "FAILED";
			case CANCELLED -> "CANCELLED";
			case PENDING -> "UNATTEMPTED";
		});
		fields.put("tran_id", tranId);
		if (txn != null && txn.valId != null) {
			fields.put("val_id", txn.valId);
		}
		fields.put("amount", txn == null ? "0.00" : txn.amount);
		fields.put("currency", "BDT");
		fields.put("value_a", txn == null ? "" : txn.reference);
		fields.put("verify_sign", "0".repeat(32));
		fields.put("verify_key", "amount,currency,status,tran_id,val_id,value_a");
		String body = fields.entrySet()
			.stream()
			.map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
			.collect(Collectors.joining("&"));
		return new ProviderCallback(SslCommerzGateway.PROVIDER, "application/x-www-form-urlencoded", Map.of(), body);
	}

	// ------------------------------------------------------------------ endpoints

	private void session(HttpExchange exchange) throws IOException {
		Map<String, String> form = decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		sessionRequests.add(form);
		if (!"POST".equals(exchange.getRequestMethod()) || !credentialsOk(form)) {
			reply(exchange, Map.of("status", "FAILED", "failedreason", "Store Credential Error Or Store is De-active"));
			return;
		}
		String tranId = form.get("tran_id");
		transactions.put(tranId, new Txn(tranId, form.get("total_amount"), form.get("currency"), form.get("value_a"),
				"PENDING", null, false, "0"));
		reply(exchange, Map.of("status", "SUCCESS", "failedreason", "", "sessionkey", UUID.randomUUID().toString(),
				"GatewayPageURL", baseUrl() + "/gwprocess/v4/gw.php?Q=pay&SESSIONKEY=" + tranId));
	}

	private void validation(HttpExchange exchange) throws IOException {
		requestUris.add(exchange.getRequestURI());
		Map<String, String> query = decode(exchange.getRequestURI().getRawQuery());
		if (!credentialsOk(query)) {
			reply(exchange, Map.of("status", "INVALID_TRANSACTION"));
			return;
		}
		String valId = query.get("val_id");
		Txn txn = transactions.values().stream().filter(t -> valId != null && valId.equals(t.valId)).findFirst().orElse(null);
		if (txn == null) {
			reply(exchange, Map.of("status", "INVALID_TRANSACTION"));
			return;
		}
		Map<String, Object> body = txn.fields();
		body.put("status", txn.validated ? "VALIDATED" : "VALID");
		transactions.put(txn.tranId, new Txn(txn.tranId, txn.amount, txn.currency, txn.reference, txn.status, txn.valId,
				true, txn.riskLevel));
		reply(exchange, body);
	}

	private void query(HttpExchange exchange) throws IOException {
		requestUris.add(exchange.getRequestURI());
		Map<String, String> query = decode(exchange.getRequestURI().getRawQuery());
		if (!credentialsOk(query)) {
			reply(exchange, Map.of("APIConnect", "INVALID_REQUEST"));
			return;
		}
		Txn txn = transactions.get(query.getOrDefault("tran_id", ""));
		List<Map<String, Object>> elements = new ArrayList<>();
		if (txn != null) {
			Map<String, Object> element = txn.fields();
			element.put("status", txn.status);
			elements.add(element);
		}
		reply(exchange, Map.of("APIConnect", "DONE", "no_of_trans_found", elements.size(), "element", elements));
	}

	private static boolean credentialsOk(Map<String, String> params) {
		return STORE_ID.equals(params.get("store_id")) && STORE_PASSWORD.equals(params.get("store_passwd"));
	}

	private void reply(HttpExchange exchange, Map<String, ?> body) throws IOException {
		byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	static Map<String, String> decode(@Nullable String encoded) {
		Map<String, String> fields = new LinkedHashMap<>();
		if (encoded == null || encoded.isEmpty()) {
			return fields;
		}
		for (String pair : encoded.split("&")) {
			int eq = pair.indexOf('=');
			fields.put(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
					eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return fields;
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private record Txn(String tranId, String amount, String currency, String reference, String status,
			@Nullable String valId, boolean validated, String riskLevel) {

		Txn with(String newStatus) {
			String id = "VALID".equals(newStatus) && valId == null ? "val" + tranId.substring(2, 12) : valId;
			return new Txn(tranId, amount, currency, reference, newStatus, id, validated, riskLevel);
		}

		Map<String, Object> fields() {
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("tran_id", tranId);
			fields.put("val_id", valId == null ? "" : valId);
			fields.put("amount", amount);
			fields.put("store_amount", amount);
			fields.put("currency", "BDT");
			fields.put("currency_type", currency);
			fields.put("currency_amount", amount);
			fields.put("bank_tran_id", valId == null ? "" : "bank" + valId);
			fields.put("value_a", reference);
			fields.put("risk_level", riskLevel);
			fields.put("risk_title", "1".equals(riskLevel) ? "High" : "Safe");
			return fields;
		}

	}

}
