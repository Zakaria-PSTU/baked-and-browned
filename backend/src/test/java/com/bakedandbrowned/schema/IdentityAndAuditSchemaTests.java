package com.bakedandbrowned.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdentityAndAuditSchemaTests extends AbstractSchemaTest {

	@Test
	void phoneMustBeNormalizedBangladeshiMobile() {
		for (String invalid : new String[] { "01712345678", "+8801212345678", "+880171234567", "+88017123456789" }) {
			assertRejectedBy("ck_phone", () -> jdbc.update(
					"INSERT INTO users (full_name, phone, password_hash) VALUES ('X', ?, 'h')", invalid));
		}
		jdbc.update("INSERT INTO users (full_name, phone, password_hash) VALUES ('X', '+8801912345678', 'h')");
	}

	@Test
	void emailIsUniqueCaseInsensitively() {
		jdbc.update("INSERT INTO users (full_name, phone, email, password_hash) VALUES ('A', ?, 'Rina@Example.com', 'h')",
				phone());
		assertRejectedBy("uq_users_email_ci", () -> jdbc.update(
				"INSERT INTO users (full_name, phone, email, password_hash) VALUES ('B', ?, 'rina@example.COM', 'h')",
				phone()));
	}

	@Test
	void updatedAtIsMaintainedByTrigger() {
		UUID id = user();
		jdbc.update("UPDATE users SET updated_at = '2000-01-01T00:00:00Z' WHERE id = ?", id);
		// The trigger overrides any explicit value with the statement's transaction time.
		OffsetDateTime afterExplicitSet = jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?",
				OffsetDateTime.class, id);
		assertThat(afterExplicitSet.getYear()).isGreaterThan(2000);

		jdbc.update("UPDATE users SET full_name = 'Renamed' WHERE id = ?", id);
		OffsetDateTime afterRename = jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?",
				OffsetDateTime.class, id);
		assertThat(afterRename).isAfterOrEqualTo(afterExplicitSet);
	}

	@Test
	void refreshTokenRequiresFamilyForReuseDetection() {
		UUID userId = user();
		assertRejectedWith(NOT_NULL_VIOLATION, "refresh_tokens", () -> jdbc.update("""
				INSERT INTO refresh_tokens (user_id, token_hash, expires_at, family_expires_at)
				VALUES (?, repeat('b', 64), now() + interval '7 days', now() + interval '30 days')""", userId));

		UUID first = jdbc.queryForObject("""
				INSERT INTO refresh_tokens (user_id, token_hash, expires_at, family_id, family_expires_at)
				VALUES (?, repeat('c', 64), now() + interval '7 days', gen_random_uuid(), now() + interval '30 days')
				RETURNING id""",
				UUID.class, userId);
		jdbc.update("""
				INSERT INTO refresh_tokens (user_id, token_hash, expires_at, family_id, family_expires_at)
				SELECT user_id, repeat('d', 64), expires_at, family_id, family_expires_at FROM refresh_tokens
				WHERE id = ?""", first);
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT family_id) FROM refresh_tokens", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void refreshTokenNeverOutlivesItsFamily() {
		UUID userId = user();
		assertRejectedWith(NOT_NULL_VIOLATION, "refresh_tokens", () -> jdbc.update("""
				INSERT INTO refresh_tokens (user_id, token_hash, expires_at, family_id)
				VALUES (?, repeat('e', 64), now() + interval '7 days', gen_random_uuid())""", userId));
		assertRejectedBy("ck_refresh_within_family", () -> jdbc.update("""
				INSERT INTO refresh_tokens (user_id, token_hash, expires_at, family_id, family_expires_at)
				VALUES (?, repeat('f', 64), now() + interval '7 days', gen_random_uuid(), now() + interval '1 day')""",
				userId));
	}

	@Test
	void auditEventsAreAppendOnly() {
		UUID adminId = admin();
		UUID eventId = jdbc.queryForObject("""
				INSERT INTO audit_events (actor_type, actor_user_id, action, entity_type, reason)
				VALUES ('ADMIN', ?, 'ZONE_ACTIVATED', 'delivery_zone', 'fees confirmed') RETURNING id""",
				UUID.class, adminId);

		assertAppendOnly("audit_events",
				() -> jdbc.update("UPDATE audit_events SET reason = 'tampered' WHERE id = ?", eventId));
		assertAppendOnly("audit_events", () -> jdbc.update("DELETE FROM audit_events WHERE id = ?", eventId));
		assertThat(jdbc.queryForObject("SELECT reason FROM audit_events WHERE id = ?", String.class, eventId))
			.isEqualTo("fees confirmed");
	}

	@Test
	void humanAuditActorsMustBeIdentified() {
		assertRejectedWith(CHECK_VIOLATION, "audit_events", () -> jdbc.update("""
				INSERT INTO audit_events (actor_type, action, entity_type) VALUES ('ADMIN', 'X', 'order')"""));
		// System and gateway actors have no user.
		jdbc.update("INSERT INTO audit_events (actor_type, action, entity_type) VALUES ('SYSTEM', 'HOLD_EXPIRED', 'order')");
	}

}
