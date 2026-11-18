package com.bakedandbrowned;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against a throwaway Testcontainers PostgreSQL
 * (no Compose or password needed): {@code ./mvnw spring-boot:test-run}.
 */
public class TestBakedAndBrownedApplication {

	public static void main(String[] args) {
		SpringApplication.from(BakedAndBrownedApplication::main).with(TestcontainersConfiguration.class)
			// Local runs only: no key in the environment, so use a throwaway signing key.
			.run(withEphemeralKey(args));
	}

	private static String[] withEphemeralKey(String[] args) {
		String[] all = Arrays.copyOf(args, args.length + 1);
		all[args.length] = "--app.security.jwt.allow-ephemeral-key=true";
		return all;
	}

}
