package com.bakedandbrowned.common.time;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single business clock. Instants are stored as UTC {@code TIMESTAMPTZ}; delivery dates,
 * "today", cutoffs and reports are interpreted in {@link #ZONE} through the injected {@link Clock}
 * (never {@code LocalDate.now()} or the JVM default zone), so tests can pin time.
 */
@Configuration(proxyBeanMethods = false)
public class BusinessTime {

	public static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");

	@Bean
	Clock clock() {
		return Clock.system(ZONE);
	}

}