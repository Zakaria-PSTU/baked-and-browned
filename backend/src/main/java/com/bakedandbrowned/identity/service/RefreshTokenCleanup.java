package com.bakedandbrowned.identity.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes refresh-token families whose newest token expired more than the retention period ago.
 * Whole families go in one statement, so the {@code replaced_by_id} self-references never block the
 * delete, and spent tokens are kept as long as any token in the family could still be presented
 * (reuse detection needs them). Idempotent and safe to run concurrently.
 */
@Component
public class RefreshTokenCleanup {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

	private final JdbcTemplate jdbc;

	private final Duration retention;

	public RefreshTokenCleanup(JdbcTemplate jdbc,
			@Value("${app.security.refresh-token-retention}") Duration retention) {
		this.jdbc = jdbc;
		this.retention = retention;
	}

	@Scheduled(cron = "${app.security.refresh-token-cleanup-cron}", zone = "Asia/Dhaka")
	void scheduledPurge() {
		int deleted = purgeExpiredFamilies();
		if (deleted > 0) {
			log.info("Deleted {} expired refresh tokens", deleted);
		}
	}

	/** Returns the number of tokens deleted. */
	public int purgeExpiredFamilies() {
		return jdbc.update("""
				DELETE FROM refresh_tokens WHERE family_id IN (
				    SELECT family_id FROM refresh_tokens
				    GROUP BY family_id
				    HAVING max(expires_at) < now() - make_interval(secs => ?))""", retention.toSeconds());
	}

}
