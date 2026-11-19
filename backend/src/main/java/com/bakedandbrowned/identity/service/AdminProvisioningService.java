package com.bakedandbrowned.identity.service;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.common.value.InvalidValueException;
import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.entity.Role;
import com.bakedandbrowned.identity.entity.UserAccount;
import com.bakedandbrowned.identity.repository.UserAccountRepository;

/**
 * Creates ADMIN accounts for the operator-run {@code provision-admin} command (ADR-0013, ADR-0024).
 * <ul>
 * <li><b>First</b>: the bootstrap ADMIN; refused once any ADMIN exists.</li>
 * <li><b>Additional</b>: only on behalf of an existing, enabled ADMIN whose credentials are
 * verified here.</li>
 * </ul>
 * Either way the target must be a brand-new account: an existing phone or email (a customer's
 * included) is refused, so no account is ever converted to ADMIN. A transaction-scoped advisory
 * lock serializes runs; the unique phone and email indexes are the backstop. Every attempt is
 * audited exactly once: a success in the same transaction as the account (if that write fails, the
 * account is rolled back), a refusal or failure in its own transaction so that it survives the
 * rollback. If that record cannot be written either, the attempt ends in
 * {@link ProvisioningAuditFailure}. Passwords and secrets are never recorded.
 */
@Service
public class AdminProvisioningService {

	/** Arbitrary constant key for {@code pg_advisory_xact_lock}. */
	static final long LOCK_KEY = 0x4241_4b45_4144_4d4eL;

	public static final String PROVISIONED = "admin.provisioned";

	public static final String REFUSED = "admin.provisioning_refused";

	/** An attempt that failed for an operational reason (database, audit write) and was rolled back. */
	public static final String FAILED = "admin.provisioning_failed";

	private static final Logger log = LoggerFactory.getLogger(AdminProvisioningService.class);

	/** Audit reason codes for refusals. */
	public static final String INVALID_NAME = "INVALID_NAME", WEAK_PASSWORD = "WEAK_PASSWORD",
			ADMIN_ALREADY_EXISTS = "ADMIN_ALREADY_EXISTS", ACCOUNT_EXISTS = "ACCOUNT_EXISTS",
			PROVISIONER_NOT_FOUND = "PROVISIONER_NOT_FOUND", PROVISIONER_BAD_PASSWORD = "PROVISIONER_BAD_PASSWORD",
			PROVISIONER_NOT_ADMIN = "PROVISIONER_NOT_ADMIN", PROVISIONER_DISABLED = "PROVISIONER_DISABLED",
			PROVISIONING_FAILED = "PROVISIONING_FAILED";

	public enum Mode {
		FIRST, ADDITIONAL
	}

	private final UserAccountRepository users;

	private final PasswordEncoder passwordEncoder;

	private final JdbcTemplate jdbc;

	private final AuditLog audit;

	private final TransactionTemplate transaction;

	private final TransactionTemplate separateTransaction;

	/** Verified against when the named provisioner does not exist, so timing does not reveal it. */
	private volatile @Nullable String timingDummyHash;

	public AdminProvisioningService(UserAccountRepository users, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
			AuditLog audit, PlatformTransactionManager transactionManager) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jdbc = jdbc;
		this.audit = audit;
		this.transaction = new TransactionTemplate(transactionManager);
		this.separateTransaction = new TransactionTemplate(transactionManager);
		this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** The bootstrap ADMIN. Refused (and audited) if any ADMIN exists or the phone or email is taken. */
	public UUID provisionFirstAdmin(String fullName, PhoneNumber phone, @Nullable String email, String password) {
		return provision(new Attempt(Mode.FIRST, phone, email), null, null, fullName, password);
	}

	/**
	 * An additional ADMIN, authorized by the existing enabled ADMIN identified by
	 * {@code provisionerIdentifier} (phone in +880 form, or email) and {@code provisionerPassword}.
	 */
	public UUID provisionAdditionalAdmin(String provisionerIdentifier, String provisionerPassword, String fullName,
			PhoneNumber phone, @Nullable String email, String password) {
		return provision(new Attempt(Mode.ADDITIONAL, phone, email), provisionerIdentifier, provisionerPassword, fullName,
				password);
	}

	private UUID provision(Attempt attempt, @Nullable String provisionerIdentifier,
			@Nullable String provisionerPassword, String fullName, String password) {
		UUID id;
		try {
			// The account and its success record commit together or not at all.
			id = transaction
				.execute(status -> create(attempt, provisionerIdentifier, provisionerPassword, fullName, password));
		}
		catch (InvalidAdminInput ex) {
			recordOutcome(attempt, REFUSED, ex.code, ex);
			throw ex;
		}
		catch (ProvisioningRefused ex) {
			recordOutcome(attempt, REFUSED, ex.code, ex);
			throw ex;
		}
		catch (DataIntegrityViolationException ex) {
			if (!isUniqueViolation(ex)) {
				throw failed(attempt, ex);
			}
			// A concurrent run or registration won the unique phone/email index after our check.
			ProvisioningRefused refused = accountExists();
			recordOutcome(attempt, REFUSED, ACCOUNT_EXISTS, refused);
			throw refused;
		}
		catch (RuntimeException ex) {
			// Anything else, a failed success-audit write included: rolled back, nothing created.
			throw failed(attempt, ex);
		}
		if (id == null) {
			throw failed(attempt, new IllegalStateException("Provisioning returned no account"));
		}
		return id;
	}

	private ProvisioningFailed failed(Attempt attempt, RuntimeException cause) {
		ProvisioningFailed failed = new ProvisioningFailed(
				"Provisioning failed and was rolled back; no administrator was created (" + cause.getClass().getSimpleName()
						+ ").",
				cause);
		recordOutcome(attempt, FAILED, PROVISIONING_FAILED, failed);
		return failed;
	}

	/** True only for a unique-index violation (SQLSTATE 23505), never for other integrity errors. */
	private static boolean isUniqueViolation(DataIntegrityViolationException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
				return true;
			}
		}
		return false;
	}

	private UUID create(Attempt attempt, @Nullable String provisionerIdentifier, @Nullable String provisionerPassword,
			String fullName, String password) {
		if (fullName.isBlank() || fullName.strip().length() < 2 || fullName.strip().length() > 150) {
			throw new InvalidAdminInput(INVALID_NAME, "Admin full name must be 2-150 characters");
		}
		if (password.length() < 12 || password.length() > AuthService.MAX_PASSWORD_LENGTH) {
			throw new InvalidAdminInput(WEAK_PASSWORD, "Admin password must be 12-128 characters");
		}
		jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", LOCK_KEY);
		if (attempt.mode == Mode.FIRST) {
			if (users.existsByRole(Role.ADMIN)) {
				throw new ProvisioningRefused(ADMIN_ALREADY_EXISTS,
						"An ADMIN account already exists; refusing to provision another.");
			}
		}
		else {
			authorize(attempt, provisionerIdentifier, provisionerPassword);
		}
		if (users.existsByPhone(attempt.phone.value())
				|| (attempt.email != null && users.existsByEmailIgnoringCase(attempt.email))) {
			throw accountExists();
		}
		UserAccount admin = UserAccount.create(fullName.strip(), attempt.email, attempt.phone,
				passwordEncoder.encode(password), Role.ADMIN);
		UUID id = users.saveAndFlush(admin).getId();
		Map<String, Object> details = attempt.details("SUCCEEDED");
		if (attempt.provisionerId == null) {
			audit.system(PROVISIONED, "USER", id, null, details);
		}
		else {
			audit.admin(attempt.provisionerId, PROVISIONED, "USER", id, null, details);
		}
		return id;
	}

	/** Verifies the provisioner on the backend: an existing, enabled ADMIN with the right password. */
	private void authorize(Attempt attempt, @Nullable String identifier, @Nullable String password) {
		Optional<UserAccount> account = identifier == null ? Optional.empty() : findByIdentifier(identifier.strip());
		if (account.isEmpty()) {
			if (password != null && password.length() <= AuthService.MAX_PASSWORD_LENGTH) {
				passwordEncoder.matches(password, timingDummyHash());
			}
			throw provisionerRejected(PROVISIONER_NOT_FOUND);
		}
		UserAccount provisioner = account.get();
		attempt.claimedProvisionerId = provisioner.getId();
		// The password is checked first, so a wrong one never reveals the account's role or status.
		if (password == null || password.length() > AuthService.MAX_PASSWORD_LENGTH
				|| !passwordEncoder.matches(password, provisioner.getPasswordHash())) {
			throw provisionerRejected(PROVISIONER_BAD_PASSWORD);
		}
		if (provisioner.getRole() != Role.ADMIN) {
			throw provisionerRejected(PROVISIONER_NOT_ADMIN);
		}
		if (!provisioner.isEnabled()) {
			throw provisionerRejected(PROVISIONER_DISABLED);
		}
		attempt.provisionerId = provisioner.getId();
	}

	/**
	 * Records a refusal or failure in its own transaction, independent of the rolled-back attempt.
	 * Exactly one record per attempt: a success is recorded only by the transaction that created the
	 * account, and this runs only when that transaction did not commit. If even this write fails, the
	 * operator gets a clear error and the log gets non-sensitive diagnostics (mode, reason code,
	 * exception type), never credentials or contact details.
	 */
	private void recordOutcome(Attempt attempt, String action, String code, RuntimeException outcome) {
		try {
			separateTransaction.executeWithoutResult(status -> {
				Map<String, Object> details = attempt.details(action.equals(FAILED) ? "FAILED" : "REFUSED");
				if (attempt.provisionerId != null) {
					audit.admin(attempt.provisionerId, action, "USER", null, code, details);
				}
				else {
					if (attempt.claimedProvisionerId != null) {
						details.put("claimedProvisionerId", attempt.claimedProvisionerId.toString());
					}
					audit.system(action, "USER", null, code, details);
				}
			});
		}
		catch (RuntimeException auditFailure) {
			log.error("Admin provisioning ({}) ended with {} but could not be audited: {}", attempt.mode, code,
					auditFailure.getClass().getName());
			ProvisioningAuditFailure failure = new ProvisioningAuditFailure("Admin provisioning ended with " + code
					+ " and could not be recorded in audit_events; nothing was created. Check the database before retrying.",
					outcome);
			failure.addSuppressed(auditFailure);
			throw failure;
		}
	}

	private String timingDummyHash() {
		String hash = timingDummyHash;
		if (hash == null) {
			hash = passwordEncoder.encode(OpaqueTokens.generate());
			timingDummyHash = hash;
		}
		return hash;
	}

	private Optional<UserAccount> findByIdentifier(String identifier) {
		if (identifier.startsWith("+")) {
			try {
				return users.findByPhone(PhoneNumber.of(identifier).value());
			}
			catch (InvalidValueException ex) {
				return Optional.empty();
			}
		}
		return identifier.contains("@") ? users.findByEmailIgnoringCase(identifier) : Optional.empty();
	}

	private static ProvisioningRefused provisionerRejected(String code) {
		return new ProvisioningRefused(code, "The provisioner could not be authenticated as an enabled ADMIN.");
	}

	private static ProvisioningRefused accountExists() {
		return new ProvisioningRefused(ACCOUNT_EXISTS,
				"An account with this phone number or email already exists; existing accounts are never made ADMIN.");
	}

	/** What is known about one provisioning attempt, for its audit record. */
	private static final class Attempt {

		final Mode mode;

		final PhoneNumber phone;

		final @Nullable String email;

		/** Set once the provisioner has been fully authorized. */
		@Nullable UUID provisionerId;

		/** The account the provisioner credentials named, even if they were then rejected. */
		@Nullable UUID claimedProvisionerId;

		Attempt(Mode mode, PhoneNumber phone, @Nullable String email) {
			this.mode = mode;
			this.phone = phone;
			this.email = email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
		}

		/** Mode, outcome and the masked target; never a password or the full phone or email. */
		Map<String, Object> details(String outcome) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("mode", mode.name());
			details.put("outcome", outcome);
			String digits = phone.value();
			details.put("targetPhone", "***" + digits.substring(Math.max(0, digits.length() - 3)));
			details.put("targetEmailGiven", email != null);
			// The OS account the process runs as, as the JVM reports it: not a verified operator identity.
			details.put("processAccount", System.getProperty("user.name", "unknown"));
			if (provisionerId != null) {
				details.put("provisionedBy", provisionerId.toString());
			}
			return details;
		}

	}

	/** Invalid input for the new admin (name, password). */
	public static final class InvalidAdminInput extends IllegalArgumentException {

		private static final long serialVersionUID = 1L;

		private final String code;

		InvalidAdminInput(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}

	}

	/** Provisioning failed for an operational reason and was rolled back. */
	public static final class ProvisioningFailed extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		ProvisioningFailed(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/** The refusal or failure could not be audited; the attempt still created nothing. */
	public static final class ProvisioningAuditFailure extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		ProvisioningAuditFailure(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/** A refused provisioning (state or authorization). */
	public static final class ProvisioningRefused extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		private final String code;

		ProvisioningRefused(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}

	}

}
