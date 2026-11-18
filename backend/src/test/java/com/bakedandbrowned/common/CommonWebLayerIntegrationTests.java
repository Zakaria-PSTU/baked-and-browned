package com.bakedandbrowned.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.bakedandbrowned.IntegrationTest;
import com.bakedandbrowned.common.time.BusinessTime;

/** The common web layer wired into the full application, not just the MVC slice. */
@IntegrationTest
class CommonWebLayerIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private Clock clock;

	@Test
	void unknownApiPathsAreProblemJsonWithTraceId() throws Exception {
		mockMvc.perform(get("/api/v1/does-not-exist").with(jwt()))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.traceId").value(Matchers.matchesPattern("^[0-9a-f]{32}$")));
	}

	@Test
	void defaultDenyHidesUnknownPathsFromAnonymousCallers() throws Exception {
		mockMvc.perform(get("/api/v1/does-not-exist"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.traceId").value(Matchers.matchesPattern("^[0-9a-f]{32}$")));
	}

	@Test
	void testOnlyProbeEndpointsAreNotPartOfTheApplication() throws Exception {
		mockMvc.perform(get("/probe/conflict").with(jwt())).andExpect(status().isNotFound());
	}

	@Test
	void theSingleBusinessClockIsInDhaka() {
		assertThat(clock.getZone()).isEqualTo(BusinessTime.ZONE);
		assertThat(BusinessTime.ZONE.getId()).isEqualTo("Asia/Dhaka");
	}

}