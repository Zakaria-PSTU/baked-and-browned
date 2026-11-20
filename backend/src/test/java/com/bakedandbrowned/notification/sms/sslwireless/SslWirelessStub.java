package com.bakedandbrowned.notification.sms.sslwireless;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for the ISMS Plus single-SMS endpoint, answering with the fields, status codes
 * and per-recipient statuses of the public documentation (ismsplus.sslwireless.com/api-documentation).
 * Like the real service as documented, it remembers every accepted {@code csms_id} and reports a
 * reused one as a duplicate. What the documentation leaves open (the HTTP status sent with an error,
 * how long references are remembered) is this stub's assumption: errors come with HTTP 200 unless a
 * test asks otherwise.
 */
final class SslWirelessStub implements AutoCloseable {

	static final String TOKEN = "stub-token-0123456789-abcdefghij-0123456789-abcdef";

	static final String SENDER_ID = "8809612345678";

	static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

	private final HttpServer server;

	private final JsonMapper json = JsonMapper.builder().build();

	private final Set<String> accepted = ConcurrentHashMap.newKeySet();

	/** Scripted answers for the next requests, oldest first; empty means "behave normally". */
	private final Queue<Answer> next = new ConcurrentLinkedQueue<>();

	/** Every request body as the adapter sent it. */
	final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();

	final List<String> methods = new CopyOnWriteArrayList<>();

	final List<String> contentTypes = new CopyOnWriteArrayList<>();

	SslWirelessStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/v3/send-sms", this::send);
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3";
	}

	SslWirelessProperties properties() {
		return properties(baseUrl());
	}

	static SslWirelessProperties properties(String baseUrl) {
		// Generous for a busy build machine; the timeout test answers later still.
		return new SslWirelessProperties(true, URI.create(baseUrl), TOKEN, SENDER_ID, Duration.ofSeconds(5),
				READ_TIMEOUT);
	}

	// ------------------------------------------------------------------ scripting

	/** The next request is answered with this top-level {@code status_code} (an error). */
	void failNextWith(int statusCode) {
		next.add(new Answer(200, statusCode, null, null, 0));
	}

	/** The next request is accepted, but this recipient gets {@code sms_status} (INVALID, BLOCKED). */
	void refuseRecipientNext(String smsStatus) {
		next.add(new Answer(200, 200, smsStatus, null, 0));
	}

	/** The next request gets this HTTP status and raw body. */
	void answerNextRaw(int httpStatus, String body) {
		next.add(new Answer(httpStatus, 0, null, body, 0));
	}

	/** The next request is accepted, but the answer comes after {@code delay} (past the read timeout). */
	void acceptSlowlyNext(Duration delay) {
		next.add(new Answer(200, 200, null, null, delay.toMillis()));
	}

	// ------------------------------------------------------------------ endpoint

	private void send(HttpExchange exchange) throws IOException {
		methods.add(exchange.getRequestMethod());
		contentTypes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
		Map<String, Object> body = json.readValue(exchange.getRequestBody().readAllBytes(),
				new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
				});
		requests.add(body);
		Answer answer = next.poll();
		if (answer != null && answer.raw != null) {
			reply(exchange, answer.httpStatus, answer.raw);
			return;
		}
		if (!TOKEN.equals(body.get("api_token"))) {
			replyError(exchange, 4001, "Unauthorized");
			return;
		}
		if (answer != null && answer.delayMillis > 0) {
			try {
				Thread.sleep(answer.delayMillis);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		if (answer != null && answer.statusCode != 200) {
			replyError(exchange, answer.statusCode, "Scripted error");
			return;
		}
		String csmsId = String.valueOf(body.get("csms_id"));
		String msisdn = String.valueOf(body.get("msisdn"));
		if (answer != null && answer.smsStatus != null) {
			reply(exchange, 200, json.writeValueAsString(Map.of("status", "SUCCESS", "status_code", 200,
					"error_message", "", "smsinfo", List.of(info(answer.smsStatus, "Refused", msisdn, csmsId, null)))));
			return;
		}
		if (!accepted.add(csmsId)) {
			reply(exchange, 200, json.writeValueAsString(Map.of("status", "FAILED", "status_code", 4023,
					"error_message", "Duplicate CMS ID",
					"smsinfo", List.of(info("DUPLICATE", "Duplicated CSMS ID", msisdn, csmsId, null)))));
			return;
		}
		reply(exchange, 200, json.writeValueAsString(Map.of("status", "SUCCESS", "status_code", 200, "error_message", "",
				"smsinfo", List.of(info("SUCCESS", "Success", msisdn, csmsId, UUID.randomUUID().toString())))));
	}

	private static Map<String, Object> info(String status, String message, String msisdn, String csmsId,
			@org.jspecify.annotations.Nullable String referenceId) {
		Map<String, Object> info = new java.util.LinkedHashMap<>();
		info.put("sms_status", status);
		info.put("status_message", message);
		info.put("msisdn", msisdn);
		info.put("sms_type", "EN");
		info.put("csms_id", csmsId);
		if (referenceId != null) {
			info.put("reference_id", referenceId);
		}
		return info;
	}

	private void replyError(HttpExchange exchange, int code, String message) throws IOException {
		reply(exchange, 200, json.writeValueAsString(
				Map.of("status", "FAILED", "status_code", code, "error_message", message, "smsinfo", List.of())));
	}

	private static void reply(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private record Answer(int httpStatus, int statusCode, @org.jspecify.annotations.Nullable String smsStatus,
			@org.jspecify.annotations.Nullable String raw, long delayMillis) {
	}

}
