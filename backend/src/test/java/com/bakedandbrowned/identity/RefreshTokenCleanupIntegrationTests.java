package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bakedandbrowned.identity.service.RefreshTokenCleanup;

class RefreshTokenCleanupIntegrationTests extends IdentityIntegrationTest {

	@Autowired
	private RefreshTokenCleanup cleanup;

	@Test
	void deletesOnlyFamiliesWhoseNewestTokenExpiredBeyondRetention() {
		UUID user = jdbc.queryForObject(
				"INSERT INTO users (full_name, phone, password_hash) VALUES ('U', ?, 'h') RETURNING id", UUID.class, phone());
		// Rotated family, entirely expired 8 days ago (retention 7 days): the self-reference must not block the delete.
		UUID oldFamily = family(user, "8 days", "9 days");
		// Rotated family whose predecessor is long expired but whose successor is recent: kept whole.
		UUID mixedFamily = family(user, "6 days", "20 days");
		// Live family.
		UUID liveFamily = UUID.randomUUID();
		token(user, liveFamily, "now() + interval '14 days'", null);

		assertThat(cleanup.purgeExpiredFamilies()).isEqualTo(2);
		assertThat(jdbc.queryForList("SELECT DISTINCT family_id FROM refresh_tokens", UUID.class))
			.containsExactlyInAnyOrder(mixedFamily, liveFamily)
			.doesNotContain(oldFamily);
		assertThat(cleanup.purgeExpiredFamilies()).isZero();
	}

	/** Two tokens, the older replaced by the newer; returns the family id. */
	private UUID family(UUID user, String newestExpiredAgo, String oldestExpiredAgo) {
		UUID family = UUID.randomUUID();
		UUID newest = token(user, family, "now() - interval '" + newestExpiredAgo + "'", null);
		token(user, family, "now() - interval '" + oldestExpiredAgo + "'", newest);
		return family;
	}

	private UUID token(UUID user, UUID family, String expiresAtSql, UUID replacedBy) {
		return jdbc.queryForObject("INSERT INTO refresh_tokens (user_id, token_hash, family_id, expires_at, used_at, "
				+ "replaced_by_id) VALUES (?, ?, ?, " + expiresAtSql + ", " + (replacedBy == null ? "NULL" : "now()")
				+ ", ?) RETURNING id", UUID.class, user, UUID.randomUUID().toString().replace("-", "").repeat(2), family,
				replacedBy);
	}

}
