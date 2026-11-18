package com.bakedandbrowned;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class BakedAndBrownedApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthEndpointReportsUp() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void livenessAndReadinessProbesAreAvailable() throws Exception {
		mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
	}

	@Test
	void nonHealthActuatorEndpointsAreNotExposed() throws Exception {
		// Authenticated, so default deny (401) does not mask whether the endpoint exists.
		mockMvc.perform(get("/actuator/env").with(jwt())).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/beans").with(jwt())).andExpect(status().isNotFound());
	}

}
