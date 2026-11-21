package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.identity.service.AdminProvisioningService;
import com.bakedandbrowned.identity.service.AuthService;

/**
 * Regression tests for NEW-1 (ADR-0025, part 1): a completed session revocation leaves no usable
 * refresh token, even when it races a refresh of the same family, and concurrent reuse detections
 * end in 401s, not a database deadlock.
 * <p>
 * Each test makes the interleaving deterministic with a temporary trigger in the isolated test
 * database: the refresh transaction, which already holds its locks, is held for a fixed time
 * inside its INSERT (or a revocation inside its UPDATE), and the competing operation starts
 * during that window. Before the per-family advisory lock in {@link AuthService}, the logout,
 * reuse-detection and concurrent-reuse tests failed (a live successor survived, or PostgreSQL
 * reported deadlock 40P01).
 */
class RevocationRaceIntegrationTests extends IdentityIntegrationTest {

	private static final long HOLD_MS = 1500;

	private static final long HEAD_START_MS = 300;

	private static final String ADMIN_PASSWORD = "first-admin-password-1";

	@Autowired
	private AuthService auth;

	@Autowired
	private AdminProvisioningService provisioning;

	@AfterEach
	void dropDelays() {
		jdbc.execute("DROP TRIGGER IF EXISTS qa_race_delay ON refresh_tokens");
		jdbc.execute("DROP FUNCTION IF EXISTS qa_race_delay()");
	}

	@Test
	void logoutRacingARefreshLeavesNoUsableToken() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);
		delayInsertsOfRefreshTokens();

		List<Future<String>> outcomes = race(() -> auth.refresh(session.refreshToken()).refreshToken(),
				() -> {
					auth.logout(session.refreshToken());
					return "LOGGED_OUT";
				});

		String successor = outcomes.get(0).get(30, TimeUnit.SECONDS);
		assertThat(outcomes.get(1).get(30, TimeUnit.SECONDS)).isEqualTo("LOGGED_OUT");
		dropDelays();
		report("logout vs refresh", successor);
		int live = liveTokens();
		String usable = attempt(() -> auth.refresh(successor));
		System.out.println("[race] logout vs refresh -> refreshing with the surviving successor: " + usable);
		assertThat(live).as("live refresh tokens after the logout completed").isZero();
		assertThat(usable).as("refreshing with the racing successor").isEqualTo("401");
	}

	@Test
	void reuseDetectionRacingARefreshLeavesNoUsableToken() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);
		String spent = session.refreshToken();
		String current = auth.refresh(spent).refreshToken();
		delayInsertsOfRefreshTokens();

		// One holder refreshes the current token while the spent one is replayed.
		List<Future<String>> outcomes = race(() -> auth.refresh(current).refreshToken(),
				() -> attempt(() -> auth.refresh(spent)));

		String successor = outcomes.get(0).get(30, TimeUnit.SECONDS);
		assertThat(outcomes.get(1).get(30, TimeUnit.SECONDS)).isEqualTo("401");
		dropDelays();
		report("reuse detection vs refresh", successor);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoke_reason = 'REUSE_DETECTED'",
				Integer.class))
			.as("reuse was detected")
			.isPositive();
		int live = liveTokens();
		String usable = attempt(() -> auth.refresh(successor));
		System.out.println("[race] reuse detection vs refresh -> refreshing with the surviving successor: " + usable);
		assertThat(live).as("live refresh tokens after reuse detection completed").isZero();
		assertThat(usable).as("refreshing with the racing successor").isEqualTo("401");
	}

	@Test
	void disablingTheAccountRacingARefreshLeavesNoRefreshCapability() throws Exception {
		String phone = register(phone(), null);
		Session session = login(phone, PASSWORD);
		delayInsertsOfRefreshTokens();

		List<Future<String>> outcomes = race(() -> auth.refresh(session.refreshToken()).refreshToken(), () -> {
			jdbc.update("UPDATE users SET enabled = false WHERE phone = ?", phone);
			return "DISABLED";
		});

		String successor = outcomes.get(0).get(30, TimeUnit.SECONDS);
		assertThat(outcomes.get(1).get(30, TimeUnit.SECONDS)).isEqualTo("DISABLED");
		dropDelays();
		// Disabling is lazy (there is no disable API; the next refresh revokes the family). The
		// racing refresh succeeded after the disable committed: record it, then check the successor.
		report("disable vs refresh", successor);
		assertThat(attempt(() -> auth.refresh(successor))).as("refreshing after the disable").isEqualTo("401");
		assertThat(liveTokens()).as("live refresh tokens once the next refresh has run").isZero();
	}

	@Test
	void theDisableCommandRacingARefreshLeavesNoTokenEvenAfterReEnabling() throws Exception {
		// ADR-0029: unlike a bare database update, the command revokes at once, under the family lock.
		provisioning.provisionFirstAdmin("Store Admin", PhoneNumber.of("+8801711000301"), null, ADMIN_PASSWORD);
		String phone = register(phone(), null);
		Session session = login(phone, PASSWORD);
		delayInsertsOfRefreshTokens();

		List<Future<String>> outcomes = race(() -> auth.refresh(session.refreshToken()).refreshToken(), () -> {
			provisioning.disableAccount("+8801711000301", ADMIN_PASSWORD, phone, "Phone reported stolen");
			return "DISABLED";
		});

		String successor = outcomes.get(0).get(30, TimeUnit.SECONDS);
		assertThat(outcomes.get(1).get(30, TimeUnit.SECONDS)).isEqualTo("DISABLED");
		dropDelays();
		report("disable command vs refresh", successor);
		assertThat(liveTokens()).as("live refresh tokens right after the disable, before any refresh").isZero();

		provisioning.enableAccount("+8801711000301", ADMIN_PASSWORD, phone, "Phone recovered");
		assertThat(attempt(() -> auth.refresh(successor))).as("the racing successor after re-enabling").isEqualTo("401");
		assertThat(attempt(() -> auth.refresh(session.refreshToken()))).isEqualTo("401");
	}

	@Test
	void twoConcurrentReuseDetectionsBothEndInA401() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);
		String first = session.refreshToken();
		String second = auth.refresh(first).refreshToken();
		auth.refresh(second);
		// Hold each family-revocation row update, so both replays lock their own token first.
		jdbc.execute("""
				CREATE FUNCTION qa_race_delay() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN PERFORM pg_sleep(0.5); RETURN NEW; END $$""");
		jdbc.execute("""
				CREATE TRIGGER qa_race_delay BEFORE UPDATE ON refresh_tokens FOR EACH ROW
				WHEN (OLD.revoked_at IS NULL AND NEW.revoke_reason = 'REUSE_DETECTED')
				EXECUTE FUNCTION qa_race_delay()""");

		List<Future<String>> outcomes = race(() -> attempt(() -> auth.refresh(first)),
				() -> attempt(() -> auth.refresh(second)));

		List<String> results = List.of(outcomes.get(0).get(30, TimeUnit.SECONDS),
				outcomes.get(1).get(30, TimeUnit.SECONDS));
		dropDelays();
		System.out.println("[race] concurrent reuse detections -> " + results);
		assertThat(results).as("both replays are refused as invalid, without a database error").containsOnly("401");
		assertThat(liveTokens()).isZero();
	}

	/**
	 * Without injected delays: many rounds of a logout, and of a reuse detection, started together
	 * with a refresh of the same family. Whatever the interleaving, the family ends with no usable
	 * token and every call ends in a success or a 401.
	 */
	@Test
	void revocationsStartedTogetherWithARefreshNeverLeaveAUsableToken() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			Session session = login(register(phone(), null), PASSWORD);
			String spent = session.refreshToken();
			String current = auth.refresh(spent).refreshToken();
			boolean viaLogout = round % 2 == 0;

			List<String> results = together(() -> attempt(() -> auth.refresh(current)), () -> viaLogout
					? attempt(() -> {
						auth.logout(current);
						return null;
					})
					: attempt(() -> auth.refresh(spent)));

			assertThat(results).as("round %d (%s)", round, viaLogout ? "logout" : "reuse").allMatch(
					r -> r.equals("OK") || r.equals("401"));
			assertThat(liveTokens()).as("round %d (%s): live tokens", round, viaLogout ? "logout" : "reuse").isZero();
			jdbc.execute("TRUNCATE users CASCADE");
		}
	}

	// ------------------------------------------------------------------ helpers

	private static final int ROUNDS = 20;

	/** Starts both calls at the same moment. */
	private static List<String> together(Callable<String> first, Callable<String> second) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Future<String> a = pool.submit(() -> {
				start.await();
				return first.call();
			});
			Future<String> b = pool.submit(() -> {
				start.await();
				return second.call();
			});
			start.countDown();
			return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
		}
		finally {
			pool.shutdown();
		}
	}

	/** Every new refresh token's INSERT waits {@link #HOLD_MS} inside the refreshing transaction. */
	private void delayInsertsOfRefreshTokens() {
		jdbc.execute("""
				CREATE FUNCTION qa_race_delay() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN PERFORM pg_sleep(%s); RETURN NEW; END $$""".formatted(HOLD_MS / 1000.0));
		jdbc.execute(
				"CREATE TRIGGER qa_race_delay BEFORE INSERT ON refresh_tokens FOR EACH ROW EXECUTE FUNCTION qa_race_delay()");
	}

	/** Starts {@code first}, then {@code second} {@link #HEAD_START_MS} later. */
	private static List<Future<String>> race(Callable<String> first, Callable<String> second)
			throws InterruptedException {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Future<String>> outcomes = new ArrayList<>();
		outcomes.add(pool.submit(first));
		Thread.sleep(HEAD_START_MS);
		outcomes.add(pool.submit(second));
		pool.shutdown();
		return outcomes;
	}

	/** "OK", "401" for the service's own refusal, or the exception type for anything else. */
	private static String attempt(Callable<?> call) {
		try {
			call.call();
			return "OK";
		}
		catch (ApiException ex) {
			return ex.status().value() == 401 ? "401" : "API " + ex.status().value();
		}
		catch (Exception ex) {
			Throwable root = ex;
			while (root.getCause() != null) {
				root = root.getCause();
			}
			return ex.getClass().getSimpleName() + " (" + root.getClass().getSimpleName() + ": "
					+ String.valueOf(root.getMessage()).lines().findFirst().orElse("") + ")";
		}
	}

	private int liveTokens() {
		return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE used_at IS NULL AND revoked_at IS NULL",
				Integer.class);
	}

	/** Never prints a token: only which states the family ended in. */
	private void report(String scenario, String successor) {
		System.out.println("[race] " + scenario + " -> successor issued: " + (successor != null) + "; family states: "
				+ jdbc.queryForList("""
						SELECT coalesce(revoke_reason, CASE WHEN used_at IS NULL THEN 'LIVE' ELSE 'USED' END)
						FROM refresh_tokens ORDER BY created_at""", String.class));
	}

}
