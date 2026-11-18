package com.bakedandbrowned.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.value.PhoneNumber;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@WebMvcTest(controllers = ApiExceptionHandlerTest.ProbeController.class)
@Import({ ApiExceptionHandlerTest.ProbeController.class, ApiExceptionHandlerTest.OpenSecurity.class })
class ApiExceptionHandlerTest {

	private static final String TRACE_ID = "^[0-9a-f]{32}$";

	@Autowired
	private MockMvc mockMvc;

	/**
	 * This slice tests MVC error rendering, not access rules (those are covered against the real
	 * {@code SecurityConfig} in the integration tests), so the probes are open.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class OpenSecurity {

		@Bean
		SecurityFilterChain openProbes(HttpSecurity http) throws Exception {
			return http.csrf(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.build();
		}

	}

	record Line(@NotNull Money unitPrice) {
	}

	record ProbeRequest(@NotBlank @Size(max = 10) String name, @NotNull Money price, PhoneNumber phone,
			List<@Valid Line> lines) {
	}

	/**
	 * Test-only endpoints; nested in a test class, so the full application never scans it.
	 * Deliberately not {@code @Validated}: parameter constraints use Spring MVC's built-in method
	 * validation, as real controllers must.
	 */
	@RestController
	static class ProbeController {

		@PostMapping("/probe")
		ProbeRequest echo(@Valid @RequestBody ProbeRequest request) {
			return request;
		}

		@GetMapping("/probe/page")
		String page(@RequestParam @Max(100) int size) {
			return "size " + size;
		}

		@GetMapping("/probe/conflict")
		String conflict() {
			throw ApiException.conflict("CANCELLATION_WINDOW_CLOSED", "The cancellation deadline has passed.");
		}

		@GetMapping("/probe/boom")
		String boom() {
			throw new IllegalStateException("connection to db-internal-7 refused for user secret_admin");
		}

	}

	@Test
	void validRequestsPassThroughWithMoneyWrittenAtScaleTwo() throws Exception {
		postJson("{\"name\":\"Brownie\",\"price\":850,\"phone\":\"+8801712345678\",\"lines\":[{\"unitPrice\":1.5}]}")
			.andExpect(status().isOk())
			.andExpect(content().string(
					"{\"name\":\"Brownie\",\"price\":850.00,\"phone\":\"+8801712345678\",\"lines\":[{\"unitPrice\":1.50}]}"));
	}

	@Test
	void beanValidationFailuresAre422ProblemsNamingFieldsWithoutValues() throws Exception {
		MvcResult result = postJson("{\"name\":\"far-too-long-name\",\"lines\":[{}]}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(422))
			.andExpect(jsonPath("$.title").isNotEmpty())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern(TRACE_ID)))
			.andReturn();
		String detail = result.getResponse().getContentAsString();
		assertThat(detail).contains("name: size must be between 0 and 10", "price: must not be null",
				"lines[0].unitPrice: must not be null");
		assertThat(detail).doesNotContain("far-too-long-name");
	}

	@Test
	void moneyWithThreeDecimalsIsA422NamingTheJsonPath() throws Exception {
		postJson("{\"name\":\"Brownie\",\"price\":1,\"lines\":[{\"unitPrice\":10.005}]}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.detail").value("lines[0].unitPrice: amount must have at most 2 decimal places"));
	}

	@Test
	void unnormalizedPhoneIsA422ThatDoesNotEchoTheNumber() throws Exception {
		MvcResult result = postJson("{\"name\":\"Brownie\",\"price\":1,\"phone\":\"01712345678\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.detail")
				.value("phone: phone must be a Bangladeshi mobile number in the form +8801XXXXXXXXX"))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("01712345678");
	}

	@Test
	void malformedJsonIsA400WithoutParserInternals() throws Exception {
		MvcResult result = postJson("{\"name\": \"Brownie\", \"price\": ")
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
			.andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern(TRACE_ID)))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("jackson", "line:", "column:");
	}

	@Test
	void methodParameterValidationIsA422() throws Exception {
		mockMvc.perform(get("/probe/page").param("size", "500"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.detail").value("size: must be less than or equal to 100"));
	}

	@Test
	void apiExceptionsKeepTheirStatusAndCode() throws Exception {
		mockMvc.perform(get("/probe/conflict"))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"))
			.andExpect(jsonPath("$.detail").value("The cancellation deadline has passed."));
	}

	@Test
	void unexpectedErrorsAreGeneric500sThatLeakNothing() throws Exception {
		MvcResult result = mockMvc.perform(get("/probe/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
			.andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern(TRACE_ID)))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("secret_admin", "db-internal",
				"IllegalStateException");
	}

	@Test
	void frameworkErrorsGetTheStatusNameAsCode() throws Exception {
		mockMvc.perform(get("/no-such-endpoint"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern(TRACE_ID)));
		mockMvc.perform(get("/probe"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void everyRequestGetsADistinctTraceIdAndTheMdcIsCleared() throws Exception {
		String first = traceIdOf(get("/probe/conflict"));
		String second = traceIdOf(get("/probe/conflict"));
		assertThat(first).matches(TRACE_ID).isNotEqualTo(second);
		assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
	}

	private String traceIdOf(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
		return body.replaceAll(".*\"traceId\":\"([0-9a-f]+)\".*", "$1");
	}

	private ResultActions postJson(String json) throws Exception {
		return mockMvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content(json));
	}

}