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
 * <li>Each login starts a new refresh family. Refresh locks the presented token, spends it and
 * issues its successor in the same family. Presenting a spent token again revokes the whole
 * family.</li>
 * </ul>
 */
@Service
public class AuthService {

	public static final String ACCOUNT_EXISTS = "ACCOUNT_EXISTS";

	public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

	public static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";

	static final int MAX_PASSWORD_LENGTH = 128;

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private final UserAccountRepository users;

	private final RefreshTokenRepository refreshTokens;

	private final PasswordEncoder passwordEncoder;

	private final AccessTokenIssuer accessTokens;

	private final Clock clock;

	private final Duration refreshTokenTtl;

	/** Verified against when the account does not exist, to equalize timing. */
	private final String timingDummyHash;

	public AuthService(UserAccountRepository users, RefreshTokenRepository refreshTokens,
			PasswordEncoder passwordEncoder, AccessTokenIssuer accessTokens, Clock clock,
			SecurityProperties properties) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.clock = clock;
		this.refreshTokenTtl = properties.refreshTokenTtl();
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
		return startSession(account.get(), UUID.randomUUID()).session();
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
		RefreshToken token = refreshTokens.findByTokenHashForUpdate(OpaqueTokens.sha256Hex(presentedToken))
			.orElseThrow(AuthService::invalidRefreshToken);
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
		Started successor = startSession(user, token.getFamilyId());
		token.markReplaced(now, successor.refreshToken().getId());
		return successor.session();
	}

	/** Revokes the presented token's family. Unknown or already revoked tokens are ignored. */
	@Transactional
	public void logout(@Nullable String presentedToken) {
		if (presentedToken == null || presentedToken.isEmpty()) {
			return;
		}
		refreshTokens.findByTokenHash(OpaqueTokens.sha256Hex(presentedToken))
			.ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant(), RevokeReason.LOGOUT));
	}

	/** The authenticated user; 401 if the account no longer exists or was disabled. */
	@Transactional(readOnly = true)
	public UserResponse currentUser(UUID userId) {
		return users.findById(userId)
			.filter(UserAccount::isEnabled)
			.map(UserResponse::from)
			.orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Authentication is required."));
	}

	private Started startSession(UserAccount user, UUID familyId) {
		String raw = OpaqueTokens.generate();
		Instant expiresAt = clock.instant().plus(refreshTokenTtl);
		RefreshToken stored = refreshTokens
			.save(RefreshToken.issue(user, OpaqueTokens.sha256Hex(raw), familyId, expiresAt));
		AuthResponse response = AuthResponse.bearer(accessTokens.issue(user), accessTokens.ttlSeconds(),
				UserResponse.from(user));
		return new Started(new AuthSession(response, raw), stored);
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
