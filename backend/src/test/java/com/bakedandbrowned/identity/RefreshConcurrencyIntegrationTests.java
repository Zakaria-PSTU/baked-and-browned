package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.identity.service.AuthService;

/**
 * Two simultaneous refreshes with the same token: the row lock serializes them, so exactly one
 * rotates and the other is treated as reuse, which revokes the family (including the successor
 * the winner just received). Never two live successors.
 */
class RefreshConcurrencyIntegrationTests extends IdentityIntegrationTest {

	@Autowired
	private AuthService auth;

	@Test
	void concurrentRefreshesOfOneTokenNeverForkTheFamily() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);
		int threads = 4;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> outcomes = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				outcomes.add(pool.submit(() -> {
					start.await();
					try {
						auth.refresh(session.refreshToken());
						return true;
					}
					catch (ApiException ex) {
						return false;
					}
				}));
			}
			start.countDown();
			int succeeded = 0;
			for (Future<Boolean> outcome : outcomes) {
				if (outcome.get(30, TimeUnit.SECONDS)) {
					succeeded++;
				}
			}
			assertThat(succeeded).isEqualTo(1);
		}

		assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class))
			.isZero();
		assertThat(jdbc.queryForList("SELECT DISTINCT revoke_reason FROM refresh_tokens", String.class))
			.containsExactly("REUSE_DETECTED");
	}

}
