package com.bakedandbrowned.common.security;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Default deny. Only the contract's public operations are {@code permitAll}; {@code /admin/**}
 * requires ADMIN here and again at method level ({@code @PreAuthorize} on admin controllers).
 * <p>
 * Stateless bearer authentication: no HTTP session, no form/basic login. Spring's CSRF filter is
 * off because bearer-authenticated endpoints never read cookies; the only cookie-authenticated
 * endpoints ({@code /auth/refresh}, {@code /auth/logout}) are protected by {@link CsrfGuard}
 * (double-submit token plus {@code Origin} allow-list) instead.
 * <p>
 * Each request with a valid access token is checked against the account as it is now
 * ({@link AccountStateJwtConverter}, ADR-0028): disabled or missing accounts get 401, and the role
 * comes from the database, not the token.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

	/** Public auth operations. A stale bearer header sent to them is ignored, not rejected. */
	private static final RequestMatcher PUBLIC_AUTH = new OrRequestMatcher(
			pathPattern(HttpMethod.POST, "/api/v1/auth/register"), pathPattern(HttpMethod.POST, "/api/v1/auth/login"),
			pathPattern(HttpMethod.POST, "/api/v1/auth/refresh"), pathPattern(HttpMethod.POST, "/api/v1/auth/logout"));

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProblemHandler problems, JdbcTemplate jdbc)
			throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_AUTH)
				.permitAll()
				.requestMatchers(pathPattern(HttpMethod.GET, "/api/v1/categories"),
						pathPattern(HttpMethod.GET, "/api/v1/products"),
						pathPattern(HttpMethod.GET, "/api/v1/products/{slug}"),
						pathPattern(HttpMethod.GET, "/api/v1/delivery-slots"),
						pathPattern(HttpMethod.GET, "/api/v1/delivery-zones"))
				.permitAll()
				// Gateway callbacks are verified server to server with the provider, not by user.
				.requestMatchers(pathPattern(HttpMethod.POST, "/api/v1/payments/webhooks/{provider}"))
				.permitAll()
				// The provider sends the browser back here; it only redirects to the web app.
				.requestMatchers(pathPattern(HttpMethod.GET, "/api/v1/payments/{orderId}/return"),
						pathPattern(HttpMethod.POST, "/api/v1/payments/{orderId}/return"))
				.permitAll()
				.requestMatchers(pathPattern(HttpMethod.GET, "/actuator/health"),
						pathPattern(HttpMethod.GET, "/actuator/health/**"))
				.permitAll()
				.requestMatchers(pathPattern("/error"))
				.permitAll()
				.requestMatchers(pathPattern("/api/v1/admin/**"))
				.hasRole("ADMIN")
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer(resource -> resource
				.jwt(jwt -> jwt.jwtAuthenticationConverter(new AccountStateJwtConverter(jdbc)))
				.bearerTokenResolver(bearerTokenResolver())
				.authenticationEntryPoint(problems)
				.accessDeniedHandler(problems))
			.exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems));
		return http.build();
	}

	/**
	 * Bearer tokens only from the {@code Authorization} header (never query or form parameters),
	 * and not on the public auth operations: a client refreshing with an expired access token still
	 * attached must reach {@code /auth/refresh} instead of getting 401.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
		return request -> PUBLIC_AUTH.matches(request) ? null : header.resolve(request);
	}

}
