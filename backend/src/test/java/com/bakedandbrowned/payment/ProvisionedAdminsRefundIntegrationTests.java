package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.service.AdminProvisioningService;
import com.bakedandbrowned.payment.service.RefundWorkflow;

/**
 * QA-04: a store set up only through the supported command (first ADMIN, then an additional ADMIN
 * authorized by the first, no SQL) can complete an admin-requested refund. The requester still
 * cannot approve it (SECOND_APPROVER_REQUIRED); the other ADMIN can.
 */
class ProvisionedAdminsRefundIntegrationTests extends ManualPaymentIntegrationTest {

	private static final String OWNER_PHONE = "+8801711000301", OWNER_PASSWORD = "owner-admin-password-1";

	private static final String MANAGER_PHONE = "+8801711000302", MANAGER_PASSWORD = "manager-admin-password-2";

	@Autowired
	private AdminProvisioningService provisioning;

	@Test
	void twoProvisionedAdminsCompleteTheRequestAndApprovalWorkflow() throws Exception {
		UUID ownerId = provisioning.provisionFirstAdmin("Store Owner", PhoneNumber.of(OWNER_PHONE), null, OWNER_PASSWORD);
		UUID managerId = provisioning.provisionAdditionalAdmin(OWNER_PHONE, OWNER_PASSWORD, "Shift Manager",
				PhoneNumber.of(MANAGER_PHONE), null, MANAGER_PASSWORD);
		assertThat(managerId).isNotEqualTo(ownerId);
		String owner = signIn(OWNER_PHONE, OWNER_PASSWORD, "10.200.0.1");
		String manager = signIn(MANAGER_PHONE, MANAGER_PASSWORD, "10.200.0.2");

		UUID orderId = placeOrder(bearer("CUSTOMER"), "MANUAL", slot, line(fudge, 1, six));
		decide(owner, paymentOf(orderId), "APPROVE", "Matched statement").andExpect(status().isOk());
		UUID refundId = UUID.fromString(json
			.readTree(adminPost(owner, "/api/v1/admin/payments/{id}/refunds", paymentOf(orderId), "{\"reason\":\"Box arrived crushed\"}")
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString())
			.get("id")
			.asString());

		// Separation of duties is unchanged: the requester cannot approve.
		adminPost(owner, "/api/v1/admin/refunds/{id}/decision", refundId, "{\"decision\":\"APPROVE\",\"note\":\"Mine\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.SECOND_APPROVER_REQUIRED));

		adminPost(manager, "/api/v1/admin/refunds/{id}/decision", refundId, "{\"decision\":\"APPROVE\",\"note\":\"Photo checked\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.decidedBy").value(managerId.toString()));
		adminPost(owner, "/api/v1/admin/refunds/{id}/completion", refundId, "{\"externalReference\":\"BKASH-RF-QA04\"}")
			.andExpect(status().isOk());

		Map<String, Object> refund = jdbc.queryForMap("SELECT requested_by, decided_by, status::text AS status FROM refunds WHERE id = ?",
				refundId);
		assertThat(refund).containsEntry("requested_by", ownerId)
			.containsEntry("decided_by", managerId)
			.containsEntry("status", "COMPLETED");
		assertThat(paymentStatus(orderId)).isEqualTo("REFUNDED");
		assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_events WHERE action = 'refund.requested'", UUID.class))
			.isEqualTo(ownerId);
		assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_events WHERE action = 'refund.approved'", UUID.class))
			.isEqualTo(managerId);
	}

	/** Signs in through the public login endpoint and returns the bearer header. */
	private String signIn(String phone, String password, String ip) throws Exception {
		String body = mockMvc.perform(post("/api/v1/auth/login").with(request -> {
			request.setRemoteAddr(ip);
			return request;
		})
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"identifier\":\"" + phone + "\",\"password\":\"" + password + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return "Bearer " + json.readTree(body).get("accessToken").asString();
	}

	private ResultActions adminPost(String bearer, String path, UUID id, String body) throws Exception {
		return mockMvc.perform(post(path, id)
			.header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

}
