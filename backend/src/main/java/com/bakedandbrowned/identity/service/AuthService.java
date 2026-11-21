package com.bakedandbrowned.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.security.SecurityProperties;
import com.bakedandbrowned.common.value.InvalidValueException;
import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.identity.dto.AuthResponse;
import com.bakedandbrowned.identity.dto.LoginRequest;
import com.bakedandbrowned.identity.dto.RegisterRequest;
import com.bakedandbrowned.identity.dto.UserResponse;
import com.bakedandbrowned.identity.entity.RefreshToken;
import com.bakedandbrowned.identity.entity.RevokeReason;
import com.bakedandbrowned.identity.entity.Role;
import com.bakedandbrowned.identity.entity.UserAccount;
import com.bakedandbrowned.identity.repository.RefreshTokenRepository;
import com.bakedandbrowned.identity.repository.UserAccountRepository;

/**
 * Registration, login, refresh-token rotation and logout.
 * <ul>
 * <li>Registration always creates a CUSTOMER.</li>
 * <li>Login failures are one indistinguishable 401 (unknown account, wrong password, disabled
 * account), and an unknown account still costs one Argon2 verification so timing does not reveal
 * which accounts exist.</li>
 * <li>Each login starts a new refresh family. Refresh takes the family lock, locks the presented
 * token, spends it and issues its successor in the same family. Presenting a spent token again
 * revokes the whole family.</li>
 * <li>Refresh, logout and reuse revocation all take the same per-family lock first, so a
 * revocation never misses a successor that a concurrent refresh is still issuing.</li>
 * <li>Each family ends a fixed time after its login (the role's absolute lifetime). A refresh
 * extends it by the role's idle lifetime, never past that end (ADR-0025).</li>
 * </ul>
 */
@Service
public class AuthService {

	public static final String ACCOUNT_EXISTS = "ACCOUNT_EXISTS";

	public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

	public static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";

	static final int MAX_PASSWORD_LENGTH = 128;

	/** First key of the two-int {@code pg_advisory_xact_lock} used for refresh families ("RF"). */
	static final int FAMILY_LOCK_CLASS = 0x5246;

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private final UserAccountRepository users;

	private final RefreshTokenRepository refreshTokens;

	private final PasswordEncoder passwordEncoder;

	private final AccessTokenIssuer accessTokens;

	private final Clock clock;

	private final SecurityProperties.Sessions sessions;

	/** Verified against when the account does not exist, to equalize timing. */
	private final String timingDummyHash;

	private final JdbcTemplate jdbc;

	public AuthService(UserAccountRepository users, RefreshTokenRepository refreshTokens,
			PasswordEncoder passwordEncoder, AccessTokenIssuer accessTokens, Clock clock,
			SecurityProperties properties, JdbcTemplate jdbc) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.clock = clock;
		this.sessions = properties.sessions();
		this.timingDummyHash = passwordEncoder.encode(OpaqueTokens.generate());
	}

	@Transactional
	public UserResponse register(RegisterRequest request) {
		String email = normalizeEmail(request.email());
		if (users.existsByPhone(request.phone().value()) || (email != null && users.existsByEmailIgnoringCase(email))) {
			throw accountExists();
		}
		UserAccount user = UserAccount.create(request.fullName().strip(), email, request.phone(),
				passwordEncoder.encode(request.password()), Role.CUSTOMER);
		try {
			users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent registration won the unique phone/email index.
			throw accountExists();
		}
		return UserResponse.from(user);
	}

	@Transactional
	public AuthSession login(LoginRequest request) {
		Optional<UserAccount> account = findByIdentifier(request.identifier().strip());
		String password = request.password();
		boolean verified;
		if (password.length() > MAX_PASSWORD_LENGTH) {
			verified = false; // never hash unbounded input
		}
		else if (account.isPresent()) {
			verified = passwordEncoder.matches(password, account.get().getPasswordHash());
		}
		else {
			passwordEncoder.matches(password, timingDummyHash);
			verified = false;
		}
		if (!verified || !account.get().isEnabled()) {
			throw ApiException.unauthorized(INVALID_CREDENTIALS, "The phone, email or password is incorrect.");
		}
		UserAccount user = account.get();
		Instant now = clock.instant();
		return startSession(user, UUID.randomUUID(), now.plus(lifetime(user).absolute()), user.getTokenVersion(), now)
			.session();
	}

	/**
	 * Rotates {@code presentedToken}. Does not roll back on its own 401s: a detected reuse must
	 * stay revoked.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public AuthSession refresh(@Nullable String presentedToken) {
		if (presentedToken == null || presentedToken.isEmpty()) {
			throw invalidRefreshToken();
		}
		String hash = OpaqueTokens.sha256Hex(presentedToken);
		UUID familyId = refreshTokens.findFamilyIdByTokenHash(hash).orElseThrow(AuthService::invalidRefreshToken);
		lockFamily(jdbc, familyId);
		RefreshToken token = refreshTokens.findByTokenHashForUpdate(hash).orElseThrow(AuthService::invalidRefreshToken);
		Instant now = clock.instant();
		if (token.isRevoked() || token.isExpiredAt(now)) {
			throw invalidRefreshToken();
		}
		UserAccount user = token.getUser();
		if (token.isUsed()) {
			int revoked = refreshTokens.revokeFamily(token.getFamilyId(), now, RevokeReason.REUSE_DETECTED);
			log.warn("Refresh token reuse detected: revoked family {} of user {} ({} active tokens)",
					token.getFamilyId(), user.getId(), revoked);
			throw invalidRefreshToken();
		}
		if (!user.isEnabled()) {
			refreshTokens.revokeFamily(token.getFamilyId(), now, RevokeReason.ACCOUNT_DISABLED);
			throw invalidRefreshToken();
		}
		// ADR-0031: the account was disabled (and perhaps re-enabled) since this family signed in,
		// for example by hand in the database, where nothing revoked it. Never revive it.
		if (token.getTokenVersion() != user.getTokenVersion()) {
			refreshTokens.revokeFamily(token.getFamilyId(), now, RevokeReason.ACCOUNT_DISABLED);
			throw invalidRefreshToken();
		}
		Started successor = startSession(user, token.getFamilyId(), token.getFamilyExpiresAt(),
				token.getTokenVersion(), now);
		token.markReplaced(now, successor.refreshToken().getId());
		return successor.session();
	}

	/** Revokes the presented token's family. Unknown or already revoked tokens are ignored. */
	@Transactional
	public void logout(@Nullable String presentedToken) {
		if (presentedToken == null || presentedToken.isEmpty()) {
			return;
		}
		refreshTokens.findFamilyIdByTokenHash(OpaqueTokens.sha256Hex(presentedToken)).ifPresent(familyId -> {
			lockFamily(jdbc, familyId);
			refreshTokens.revokeFamily(familyId, clock.instant(), RevokeReason.LOGOUT);
		});
	}

	/**
	 * Serializes every operation on one refresh family for the rest of the transaction (ADR-0025).
	 * A revocation therefore waits for a concurrent rotation to commit and then also revokes its
	 * successor, and the family lock, always taken first, gives every caller the same lock order.
	 * The key is the two-int form, namespaced by {@link #FAMILY_LOCK_CLASS}; a hash collision
	 * between two families only serializes them, it never skips a lock.
	 */
	static void lockFamily(JdbcTemplate jdbc, UUID familyId) {
		jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", FAMILY_LOCK_CLASS, familyId.hashCode());
	}

	/** The authenticated user; 401 if the account no longer exists or was disabled. */
	@Transactional(readOnly = true)
	public UserResponse currentUser(UUID userId) {
		return users.findById(userId)
			.filter(UserAccount::isEnabled)
			.map(UserResponse::from)
			.orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Authentication is required."));
	}

	/**
	 * Issues the family's next refresh token. It expires after the role's idle lifetime, but never
	 * after {@code familyExpiresAt} (ADR-0025), and the cookie lives exactly as long as the token.
	 * Both tokens carry {@code tokenVersion}, which callers have checked equals the account's
	 * (ADR-0031).
	 */
	private Started startSession(UserAccount user, UUID familyId, Instant familyExpiresAt, int tokenVersion,
			Instant now) {
		String raw = OpaqueTokens.generate();
		Instant idleEnd = now.plus(lifetime(user).idle());
		Instant expiresAt = idleEnd.isBefore(familyExpiresAt) ? idleEnd : familyExpiresAt;
		RefreshToken stored = refreshTokens.save(RefreshToken.issue(user, OpaqueTokens.sha256Hex(raw), familyId,
				expiresAt, familyExpiresAt, tokenVersion));
		AuthResponse response = AuthResponse.bearer(accessTokens.issue(user, tokenVersion), accessTokens.ttlSeconds(),
				UserResponse.from(user));
		return new Started(new AuthSession(response, raw, Duration.between(now, expiresAt)), stored);
	}

	/** The session lifetime of the user's current role. */
	private SecurityProperties.SessionLifetime lifetime(UserAccount user) {
		return switch (user.getRole()) {
			case CUSTOMER -> sessions.customer();
			case ADMIN -> sessions.admin();
		};
	}

	private record Started(AuthSession session, RefreshToken refreshToken) {
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
		if (identifier.contains("@")) {
			return users.findByEmailIgnoringCase(identifier);
		}
		return Optional.empty();
	}

	private static @Nullable String normalizeEmail(@Nullable String email) {
		if (email == null || email.isBlank()) {
			return null;
		}
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private static ApiException accountExists() {
		return ApiException.conflict(ACCOUNT_EXISTS, "An account with this phone number or email already exists.");
	}

	private static ApiException invalidRefreshToken() {
		return ApiException.unauthorized(INVALID_REFRESH_TOKEN, "The session has expired. Please log in again.");
	}

}
