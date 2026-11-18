package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.bakedandbrowned.IntegrationTest;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared setup for identity tests: users (and, by cascade, refresh tokens) are truncated before and
 * after each test, like the schema tests do. Every request gets its own client IP so the
 * per-IP rate limits never couple unrelated tests.
 */
@IntegrationTest
abstract class IdentityIntegrationTest {

	static final String ORIGIN = "http://localhost:4200";

	static final String PASSWORD = "correct horse battery";

	private static final AtomicInteger IP = new AtomicInteger();

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected JsonMapper json;

	@BeforeEach
	@AfterEach
	void resetUsers() {
		jdbc.execute("TRUNCATE users CASCADE");
	}

	/** A unique valid Bangladeshi mobile number. */
	static String phone() {
		return "+8801" + ThreadLocalRandom.current().nextInt(3, 10) + "%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
	}

	/** A fresh client IP for one request (or a group sharing it). */
	static RequestPostProcessor fromNewIp() {
		return fromIp(newIp());
	}

	static String newIp() {
		int n = IP.incrementAndGet();
		return "10.%d.%d.%d".formatted((n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff);
	}

	static RequestPostProcessor fromIp(String ip) {
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}

	String register(String phone, String email) throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("fullName", "Nusrat Jahan");
		request.put("phone", phone);
		request.put("email", email);
		request.put("password", PASSWORD);
		String body = json.writeValueAsString(request);
		mockMvc.perform(post("/api/v1/auth/register").with(fromNewIp()).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated());
		return phone;
	}

	Session login(String identifier, String password) throws Exception {
		String body = json.writeValueAsString(Map.of("identifier", identifier, "password", password));
		MvcResult result = mockMvc
			.perform(post("/api/v1/auth/login").with(fromNewIp()).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andReturn();
		return Session.from(result, json);
	}

	/** {@code POST /auth/refresh} as a browser would send it, with a valid CSRF pair. */
	static MockHttpServletRequestBuilder refreshRequest(Session session) {
		return post("/api/v1/auth/refresh").with(fromNewIp())
			.header("Origin", ORIGIN)
			.header("X-CSRF-Token", session.xsrfToken())
			.cookie(new Cookie("refresh_token", session.refreshToken()), new Cookie("XSRF-TOKEN", session.xsrfToken()));
	}

	/** The value of the named cookie in the response's Set-Cookie headers, or null. */
	static String setCookieValue(MvcResult result, String name) {
		String header = setCookieHeader(result, name);
		return header == null ? null : header.substring(name.length() + 1, header.indexOf(';'));
	}

	static String setCookieHeader(MvcResult result, String name) {
		List<String> headers = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		return headers.stream().filter(h -> h.startsWith(name + "=")).findFirst().orElse(null);
	}

	record Session(String accessToken, String refreshToken, String xsrfToken, JsonNode body) {

		static Session from(MvcResult result, JsonMapper json) throws Exception {
			JsonNode body = json.readTree(result.getResponse().getContentAsString());
			String refresh = setCookieValue(result, "refresh_token");
			String xsrf = setCookieValue(result, "XSRF-TOKEN");
			assertThat(refresh).isNotBlank();
			assertThat(xsrf).isNotBlank();
			return new Session(body.get("accessToken").asString(), refresh, xsrf, body);
		}

		String bearer() {
			return "Bearer " + accessToken;
		}

	}

}
