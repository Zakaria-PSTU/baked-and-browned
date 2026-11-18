package com.bakedandbrowned;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context against a Testcontainers PostgreSQL with all Flyway migrations
 * applied and {@code ddl-auto=validate}, under the {@code test} profile (fake payment gateway)
 * and with scheduled jobs off (tests run jobs directly). Use the same annotation everywhere so the
 * context (and the container) is created once per test run.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = { "app.security.jwt.allow-ephemeral-key=true", "app.scheduling.enabled=false" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {

}
