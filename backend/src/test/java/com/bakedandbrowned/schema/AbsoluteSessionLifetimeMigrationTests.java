package com.bakedandbrowned.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V13 (ADR-0025) on a database that already holds sessions: migrate to V12, seed families, then
 * apply V13. Each family ends its role's absolute lifetime after its first token, and no token
 * outlives its family. Uses its own container, because the shared one is always fully migrated.
 */
class AbsoluteSessionLifetimeMigrationTests {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("postgres:17.12-alpine"));

	private static JdbcTemplate jdbc;

	private static Flyway flyway(String target) {
		return Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.target(target)
			.load();
	}

	@BeforeAll
	static void start() {
		POSTGRES.start();
		jdbc = new JdbcTemplate(
				new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	@AfterAll
	static void stop() {
		POSTGRES.stop();
	}

	@Test
	void backfillsTheAbsoluteEndFromEachFamilysFirstToken() {
		flyway("12").migrate();
		Instant now = jdbc.queryForObject("SELECT now()", Instant.class);
		UUID customer = user("+8801711000001", "CUSTOMER");
		UUID admin = user("+8801711000002", "ADMIN");

		// A customer family started 20 days ago, rotated yesterday (token valid 13 more days).
		UUID customerFamily = UUID.randomUUID();
		token(customer, customerFamily, now.minus(Duration.ofDays(20)), now.minus(Duration.ofDays(6)));
		UUID customerLive = token(customer, customerFamily, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(13)));
		// An admin family started 13 hours ago with a 14-day token: past the new 12-hour end.
		UUID adminFamily = UUID.randomUUID();
		UUID adminLive = token(admin, adminFamily, now.minus(Duration.ofHours(13)), now.plus(Duration.ofDays(14)));
		// An admin family started 2 hours ago.
		UUID freshAdminFamily = UUID.randomUUID();
		UUID freshAdmin = token(admin, freshAdminFamily, now.minus(Duration.ofHours(2)), now.plus(Duration.ofDays(14)));

		flyway("13").migrate();

		Instant customerEnd = now.minus(Duration.ofDays(20)).plus(Duration.ofDays(30));
		assertThat(familyEnd(customerFamily)).isEqualTo(customerEnd);
		assertThat(expiresAt(customerLive)).as("13 days left, within the 10 left in the family")
			.isEqualTo(customerEnd);

		Instant adminEnd = now.minus(Duration.ofHours(13)).plus(Duration.ofHours(12));
		assertThat(familyEnd(adminFamily)).isEqualTo(adminEnd);
		assertThat(expiresAt(adminLive)).as("an admin session older than 12 h has ended").isEqualTo(adminEnd)
			.isBefore(now);

		Instant freshAdminEnd = now.minus(Duration.ofHours(2)).plus(Duration.ofHours(12));
		assertThat(expiresAt(freshAdmin)).isEqualTo(freshAdminEnd);
		assertThat(familyEnd(freshAdminFamily)).isEqualTo(freshAdminEnd);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE expires_at > family_expires_at",
				Integer.class))
			.isZero();
		assertThat(jdbc.queryForObject("""
				SELECT is_nullable FROM information_schema.columns
				WHERE table_name = 'refresh_tokens' AND column_name = 'family_expires_at'""", String.class))
			.isEqualTo("NO");
	}

	private static UUID user(String phone, String role) {
		return jdbc.queryForObject("""
				INSERT INTO users (full_name, phone, password_hash, role) VALUES ('Migration User', ?, 'hash', ?::user_role)
				RETURNING id""", UUID.class, phone, role);
	}

	private static UUID token(UUID user, UUID family, Instant createdAt, Instant expiresAt) {
		return jdbc.queryForObject("""
				INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, expires_at)
				VALUES (?, ?, ?, ?, ?) RETURNING id""", UUID.class, user,
				UUID.randomUUID().toString().replace("-", "").repeat(2), family, java.sql.Timestamp.from(createdAt),
				java.sql.Timestamp.from(expiresAt));
	}

	private static Instant familyEnd(UUID family) {
		return jdbc.queryForObject("SELECT DISTINCT family_expires_at FROM refresh_tokens WHERE family_id = ?",
				Instant.class, family);
	}

	private static Instant expiresAt(UUID token) {
		return jdbc.queryForObject("SELECT expires_at FROM refresh_tokens WHERE id = ?", Instant.class, token);
	}

}
