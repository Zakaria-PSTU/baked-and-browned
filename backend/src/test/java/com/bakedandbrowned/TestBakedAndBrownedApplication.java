package com.bakedandbrowned;

import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against a throwaway Testcontainers PostgreSQL
 * (no Compose or password needed): {@code ./mvnw spring-boot:test-run}.
 */
public class TestBakedAndBrownedApplication {

	public static void main(String[] args) {
		SpringApplication.from(BakedAndBrownedApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
