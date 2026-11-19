package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.bakedandbrowned.catalog.CatalogIntegrationTest;
import com.bakedandbrowned.payment.dto.PaymentDtos.ManualPaymentProvider;
import com.bakedandbrowned.payment.service.ManualPaymentProperties;
import com.bakedandbrowned.payment.service.ManualPaymentProperties.Account;

/**
 * ADR-0022: the receiving accounts for MANUAL payments come from configuration (one bKash account in
 * the shared test context) and are shown to signed-in customers at checkout.
 */
class ManualPaymentAccountsIntegrationTests extends CatalogIntegrationTest {

	@Test
	void signedInCustomersSeeTheConfiguredAccounts() throws Exception {
		mockMvc.perform(get("/api/v1/payments/manual-accounts").header(HttpHeaders.AUTHORIZATION, bearer("CUSTOMER")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].provider").value("BKASH"))
			.andExpect(jsonPath("$[0].label").value("bKash (Merchant)"))
			.andExpect(jsonPath("$[0].accountName").value("Baked & Browned"))
			.andExpect(jsonPath("$[0].accountNumber").value("01700000000"))
			.andExpect(jsonPath("$[0].note").value("Use Payment, not Send Money."));
		mockMvc.perform(get("/api/v1/payments/manual-accounts")).andExpect(status().isUnauthorized());
	}

	@Test
	void misconfiguredAccountsFailAtStartup() {
		assertThatThrownBy(() -> new Account(ManualPaymentProvider.BKASH, " ", "Baked & Browned", "017", null))
			.hasMessageContaining("label");
		assertThatThrownBy(() -> new Account(null, "bKash", "Baked & Browned", "017", null))
			.hasMessageContaining("provider");
		assertThatThrownBy(() -> new Account(ManualPaymentProvider.NAGAD, "Nagad", "B", "0".repeat(61), null))
			.hasMessageContaining("account-number");
		org.assertj.core.api.Assertions
			.assertThat(new Account(ManualPaymentProvider.NAGAD, " Nagad ", "B", "017", "  ").note())
			.isNull();
		org.assertj.core.api.Assertions.assertThat(new ManualPaymentProperties(null).accounts()).isEqualTo(List.of());
	}

}
