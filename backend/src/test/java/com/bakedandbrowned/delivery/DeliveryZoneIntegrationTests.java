package com.bakedandbrowned.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.delivery.service.DeliveryZoneService;
import com.bakedandbrowned.delivery.service.ZoneEligibility;

/** ADR-0010 delivery zones: public list, admin management, eligibility. */
class DeliveryZoneIntegrationTests extends DeliveryIntegrationTest {

	@Autowired
	private DeliveryZoneService zones;

	@Test
	void seededZonesStayHiddenUntilAnAdminConfiguresThem() throws Exception {
		mockMvc.perform(get("/api/v1/delivery-zones")).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));

		String admin = bearer("ADMIN");
		mockMvc.perform(get("/api/v1/admin/delivery-zones").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$", hasSize(6)))
			.andExpect(jsonPath("$[0].code").value("DHANMONDI"))
			.andExpect(jsonPath("$[0].active").value(false))
			.andExpect(jsonPath("$[0].deliveryFee").isEmpty());
	}

	@Test
	void aZoneCannotBeActivatedWithoutAFee() throws Exception {
		String admin = bearer("ADMIN");
		UUID dhanmondi = zoneId("DHANMONDI");

		send(patch("/api/v1/admin/delivery-zones/" + dhanmondi), admin, zoneJson("DHANMONDI", "Dhanmondi", null, null, true))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("ZONE_FEE_REQUIRED"));
		send(patch("/api/v1/admin/delivery-zones/" + dhanmondi), admin,
				zoneJson("DHANMONDI", "Dhanmondi", "60.00", "1000.00", true))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true));

		mockMvc.perform(get("/api/v1/delivery-zones"))
			.andExpect(jsonPath("$[*].code", contains("DHANMONDI")))
			.andExpect(jsonPath("$[0].deliveryFee").value(60.00))
			.andExpect(jsonPath("$[0].minOrderAmount").value(1000.00))
			.andExpect(jsonPath("$[0].id").doesNotExist())
			.andExpect(jsonPath("$[0].active").doesNotExist());
		String details = jdbc.queryForObject("SELECT details::text FROM audit_events WHERE action = 'DELIVERY_ZONE_UPDATED'",
				String.class);
		assertThat(details).contains("\"deliveryFee\": \"60.00\"").contains("\"active\": true");
	}

	@Test
	void zonesHaveUniqueCodesAndNames() throws Exception {
		String admin = bearer("ADMIN");
		send(post("/api/v1/admin/delivery-zones"), admin, zoneJson("BASHUNDHARA", "Bashundhara", "90.00", null, true))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sortOrder").value(0));
		send(post("/api/v1/admin/delivery-zones"), admin, zoneJson("BASHUNDHARA", "Other", null, null, false))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ZONE_EXISTS"));
		send(post("/api/v1/admin/delivery-zones"), admin, zoneJson("OTHER", "Dhanmondi", null, null, false))
			.andExpect(status().isConflict());
		for (String bad : new String[] { zoneJson("bashundhara", "Lower", null, null, false),
				zoneJson("X", "Short code", null, null, false), zoneJson("NEGATIVE", "Negative", "-1", null, true),
				zoneJson("NOACTIVE", "No active flag", null, null, false).replace(",\"active\":false", "") }) {
			send(post("/api/v1/admin/delivery-zones"), admin, bad).andExpect(status().isUnprocessableContent());
		}
		mockMvc.perform(get("/api/v1/admin/delivery-zones").header(HttpHeaders.AUTHORIZATION, bearer("CUSTOMER")))
			.andExpect(status().isForbidden());
	}

	@Test
	void eligibilityUsesTheZonesFeeAndMinimum() {
		configureZone("GULSHAN", "120.00", "2000.00", true);
		configureZone("MIRPUR", "60.00", null, false);

		ZoneEligibility.EligibleZone gulshan = zones.eligibleZone(" GULSHAN ", Money.of("2000.00"));
		assertThat(gulshan.name()).isEqualTo("Gulshan");
		assertThat(gulshan.deliveryFee()).isEqualTo(Money.of("120.00"));

		assertCode("GULSHAN", "1999.99", ZoneEligibility.BELOW_MINIMUM_ORDER);
		assertCode("MIRPUR", "5000.00", ZoneEligibility.ZONE_NOT_AVAILABLE); // inactive
		assertCode("UTTARA", "5000.00", ZoneEligibility.ZONE_NOT_AVAILABLE); // no fee
		assertCode("NOWHERE", "5000.00", ZoneEligibility.ZONE_NOT_AVAILABLE);
		assertCode(null, "5000.00", ZoneEligibility.ZONE_NOT_AVAILABLE);
	}

	private void assertCode(String code, String subtotal, String expected) {
		assertThatThrownBy(() -> zones.eligibleZone(code, Money.of(subtotal)))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
	}

	private UUID zoneId(String code) {
		return jdbc.queryForObject("SELECT id FROM delivery_zones WHERE code = ?", UUID.class, code);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
		return mockMvc.perform(
				request.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String zoneJson(String code, String name, String fee, String minimum, boolean active) {
		return "{\"code\":\"%s\",\"name\":\"%s\",\"deliveryFee\":%s,\"minOrderAmount\":%s,\"active\":%s}".formatted(code,
				name, fee, minimum, active);
	}

}
