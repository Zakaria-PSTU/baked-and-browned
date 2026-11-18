package com.bakedandbrowned.identity.service;

import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.entity.Role;
import com.bakedandbrowned.identity.entity.UserAccount;
import com.bakedandbrowned.identity.repository.UserAccountRepository;

/**
 * Creates the first ADMIN (ADR-0013). Refuses if any ADMIN already exists, or if the phone or email
 * is taken. A transaction-scoped advisory lock serializes concurrent runs, so two operators cannot
 * both pass the "no admin yet" check.
 */
@Service
public class AdminProvisioningService {

	/** Arbitrary constant key for {@code pg_advisory_xact_lock}. */
	static final long LOCK_KEY = 0x4241_4b45_4144_4d4eL;

	private final UserAccountRepository users;

	private final PasswordEncoder passwordEncoder;

	private final JdbcTemplate jdbc;

	public AdminProvisioningService(UserAccountRepository users, PasswordEncoder passwordEncoder, JdbcTemplate jdbc) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jdbc = jdbc;
	}

	@Transactional
	public UUID provisionFirstAdmin(String fullName, PhoneNumber phone, @Nullable String email, String password) {
		if (fullName.isBlank() || fullName.strip().length() < 2 || fullName.strip().length() > 150) {
			throw new IllegalArgumentException("Admin full name must be 2-150 characters");
		}
		if (password.length() < 12 || password.length() > AuthService.MAX_PASSWORD_LENGTH) {
			throw new IllegalArgumentException("Admin password must be 12-128 characters");
		}
		jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", LOCK_KEY);
		if (users.existsByRole(Role.ADMIN)) {
			throw new IllegalStateException("An ADMIN account already exists; refusing to provision another.");
		}
		String normalizedEmail = email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
		if (users.existsByPhone(phone.value())
				|| (normalizedEmail != null && users.existsByEmailIgnoringCase(normalizedEmail))) {
			throw new IllegalStateException("An account with this phone number or email already exists.");
		}
		UserAccount admin = UserAccount.create(fullName.strip(), normalizedEmail, phone, passwordEncoder.encode(password),
				Role.ADMIN);
		return users.saveAndFlush(admin).getId();
	}

}
