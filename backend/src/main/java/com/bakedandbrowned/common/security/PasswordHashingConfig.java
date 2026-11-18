package com.bakedandbrowned.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Password hashing, available with or without the web layer (admin provisioning runs without it). */
@Configuration(proxyBeanMethods = false)
public class PasswordHashingConfig {

	/** Argon2id with the OWASP minimum profile: 19 MiB memory, 2 iterations, parallelism 1. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
	}

}
