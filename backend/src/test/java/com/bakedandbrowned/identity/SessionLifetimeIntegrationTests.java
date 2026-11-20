package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ADR-0025, part 2: a refresh session ends at a fixed time after its login (CUSTOMER 30 d, ADMIN
 * 12 h), and each refresh extends it by the role's idle lifetime (CUSTOMER 14 d, ADMIN 8 h) but
 * never past that end. The cookies' {@code Max-Age} always equals the token's remaining lifetime.
 * The business clock is replaced by one the tests move forward.
 */
class SessionLifetimeIntegrationTests extends IdentityIntegrationTest {

	private static final Instant START = Instant.parse("2026-11-20T06:00:00Z");

	private static final MutableClock CLOCK = new MutableClock(START);

	@TestBean
	private Clock clock;

	static Clock clock() {
		return CLOCK;
	}

	@BeforeEach
	void resetClock() {
		CLOCK.set(START);
	}

	// ------------------------------------------------------------------ customer

	@Test
	void customerRefreshingContinuouslyEndsThirtyDaysAfterLogin() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);

		CLOCK.advance(Duration.ofDays(13));
		MvcResult first = mockMvc.perform(refreshRequest(session)).andExpect(status().isOk()).andReturn();
		assertMaxAge(first, Duration.ofDays(14));

		CLOCK.advance(Duration.ofDays(13));
		MvcResult second = mockMvc.perform(refreshRequest(Session.from(first, json))).andExpect(status().isOk())
			.andReturn();
		// Day 26: the idle end (day 40) is cut to the absolute end (day 30).
		assertMaxAge(second, Duration.ofDays(4));
		assertThat(familyEnds()).containsOnly(START.plus(Duration.ofDays(30)));
		assertNoTokenOutlivesItsFamily();

		CLOCK.advance(Duration.ofDays(4));
		mockMvc.perform(refreshRequest(Session.from(second, json))).andExpect(status().isUnauthorized());
	}

	@Test
	void customerSessionEndsAfterFourteenIdleDays() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);

		CLOCK.advance(Duration.ofDays(14).minusSeconds(1));
		Session rotated = Session.from(mockMvc.perform(refreshRequest(session)).andExpect(status().isOk()).andReturn(),
				json);

		CLOCK.advance(Duration.ofDays(14));
		mockMvc.perform(refreshRequest(rotated)).andExpect(status().isUnauthorized());
	}

	@Test
	void customerLoginCookiesLastTheIdleLifetime() throws Exception {
		String phone = register(phone(), null);
		MvcResult result = mockMvc.perform(post("/api/v1/auth/login").with(fromNewIp())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("identifier", phone, "password", PASSWORD))))
			.andExpect(status().isOk())
			.andReturn();

		assertMaxAge(result, Duration.ofDays(14));
		assertThat(jdbc.queryForObject("SELECT expires_at FROM refresh_tokens", Instant.class))
			.isEqualTo(START.plus(Duration.ofDays(14)));
		assertThat(familyEnds()).containsOnly(START.plus(Duration.ofDays(30)));
	}

	// ------------------------------------------------------------------ admin

	@Test
	void adminSessionEndsTwelveHoursAfterLoginHoweverActive() throws Exception {
		Session session = login(admin(), PASSWORD);

		CLOCK.advance(Duration.ofHours(7));
		MvcResult first = mockMvc.perform(refreshRequest(session)).andExpect(status().isOk()).andReturn();
		// Hour 7: the idle end (hour 15) is cut to the absolute end (hour 12).
		assertMaxAge(first, Duration.ofHours(5));
		assertThat(familyEnds()).containsOnly(START.plus(Duration.ofHours(12)));

		CLOCK.advance(Duration.ofHours(4));
		MvcResult second = mockMvc.perform(refreshRequest(Session.from(first, json))).andExpect(status().isOk())
			.andReturn();
		assertMaxAge(second, Duration.ofHours(1));

		CLOCK.advance(Duration.ofHours(1));
		mockMvc.perform(refreshRequest(Session.from(second, json))).andExpect(status().isUnauthorized());
		assertNoTokenOutlivesItsFamily();
	}

	@Test
	void adminSessionEndsAfterEightIdleHours() throws Exception {
		Session session = login(admin(), PASSWORD);
		assertThat(jdbc.queryForObject("SELECT expires_at FROM refresh_tokens", Instant.class))
			.isEqualTo(START.plus(Duration.ofHours(8)));

		CLOCK.advance(Duration.ofHours(8));
		mockMvc.perform(refreshRequest(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void refreshUsesTheCurrentRoleButKeepsTheSessionEnd() throws Exception {
		String phone = register(phone(), null);
		Session session = login(phone, PASSWORD);
		jdbc.update("UPDATE users SET role = 'ADMIN' WHERE phone = ?", phone);

		CLOCK.advance(Duration.ofHours(1));
		MvcResult result = mockMvc.perform(refreshRequest(session)).andExpect(status().isOk()).andReturn();

		assertMaxAge(result, Duration.ofHours(8));
		assertThat(familyEnds()).containsOnly(START.plus(Duration.ofDays(30)));
	}

	// ------------------------------------------------------------------ isolation

	@Test
	void endingOneSessionLeavesTheUsersOtherSessionsAlone() throws Exception {
		String phone = register(phone(), null);
		Session early = login(phone, PASSWORD);
		CLOCK.advance(Duration.ofDays(10));
		Session late = login(phone, PASSWORD);
		// Day 10: keep the early session within its 14 idle days.
		early = Session.from(mockMvc.perform(refreshRequest(early)).andExpect(status().isOk()).andReturn(), json);

		// Day 20: both still refreshable.
		CLOCK.advance(Duration.ofDays(10));
		early = Session.from(mockMvc.perform(refreshRequest(early)).andExpect(status().isOk()).andReturn(), json);
		late = Session.from(mockMvc.perform(refreshRequest(late)).andExpect(status().isOk()).andReturn(), json);

		// Day 30: the early session has reached its absolute end; the late one (ends day 40) has not.
		CLOCK.advance(Duration.ofDays(10));
		mockMvc.perform(refreshRequest(early)).andExpect(status().isUnauthorized());
		mockMvc.perform(refreshRequest(late)).andExpect(status().isOk());
		assertThat(familyEnds()).containsExactlyInAnyOrder(START.plus(Duration.ofDays(30)),
				START.plus(Duration.ofDays(40)));
	}

	// ------------------------------------------------------------------ helpers

	/** Registers a customer and promotes it; registration itself only ever creates customers. */
	private String admin() throws Exception {
		String phone = register(phone(), null);
		jdbc.update("UPDATE users SET role = 'ADMIN' WHERE phone = ?", phone);
		return phone;
	}

	private static void assertMaxAge(MvcResult result, Duration expected) {
		for (String cookie : List.of("refresh_token", "XSRF-TOKEN")) {
			assertThat(setCookieHeader(result, cookie)).as(cookie).contains("Max-Age=" + expected.toSeconds() + ";");
		}
	}

	private List<Instant> familyEnds() {
		return jdbc.queryForList("SELECT DISTINCT family_expires_at FROM refresh_tokens", Instant.class);
	}

	private void assertNoTokenOutlivesItsFamily() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE expires_at > family_expires_at",
				Integer.class))
			.isZero();
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT family_expires_at) = count(DISTINCT family_id) FROM "
				+ "refresh_tokens", Boolean.class))
			.as("one absolute end per family")
			.isTrue();
	}

	/** A business clock the test moves; shared with request threads, hence volatile. */
	static final class MutableClock extends Clock {

		private volatile Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void set(Instant instant) {
			now = instant;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("Asia/Dhaka");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(now, zone);
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
