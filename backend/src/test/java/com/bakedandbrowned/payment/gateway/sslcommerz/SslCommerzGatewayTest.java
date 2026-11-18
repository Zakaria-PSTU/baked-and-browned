package com.bakedandbrowned.payment.gateway.sslcommerz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.PaymentSessionRequest;
import com.bakedandbrowned.payment.gateway.PaymentSessionRequest.PaymentCustomer;
import com.bakedandbrowned.payment.gateway.PaymentVerificationException;
import com.bakedandbrowned.payment.gateway.ProviderCallback;
import com.bakedandbrowned.payment.gateway.VerifiedPaymentResult;

import tools.jackson.databind.json.JsonMapper;

/** SSLCommerz specifics beyond the shared contract: what is sent, how results map, what fails closed. */
class SslCommerzGatewayTest {

	private final SslCommerzStub stub;

	private final SslCommerzGateway gateway;

	SslCommerzGatewayTest() throws IOException {
		stub = new SslCommerzStub();
		gateway = new SslCommerzGateway(stub.properties(), JsonMapper.builder().build());
	}

	@AfterEach
	void stop() {
		stub.close();
	}

	private static PaymentSessionRequest request(UUID paymentId, @org.jspecify.annotations.Nullable String email) {
		return new PaymentSessionRequest(paymentId, "BB-2026-000042", Money.of("1310.00"),
				URI.create("https://api.example.test/api/v1/payments/" + UUID.randomUUID() + "/return"),
				new PaymentCustomer("Nusrat Jahan", "+8801712345678", email, "House 12, Road 5", "Dhanmondi"));
	}

	@Test
	void theSessionRequestCarriesEveryMandatoryField() {
		UUID paymentId = UUID.randomUUID();
		var session = gateway.createSession(request(paymentId, null));

		Map<String, String> sent = stub.sessionRequests.getFirst();
		assertThat(sent).containsEntry("store_id", SslCommerzStub.STORE_ID)
			.containsEntry("total_amount", "1310.00")
			.containsEntry("currency", "BDT")
			.containsEntry("tran_id", session.sessionId())
			.containsEntry("ipn_url", "https://api.example.test/api/v1/payments/webhooks/sslcommerz")
			.containsEntry("cus_name", "Nusrat Jahan")
			// No account email: the configured fallback, since SSLCommerz requires one.
			.containsEntry("cus_email", "orders@bakedandbrowned.test")
			.containsEntry("cus_phone", "+8801712345678")
			.containsEntry("cus_add1", "House 12, Road 5")
			.containsEntry("cus_city", "Dhaka")
			.containsEntry("cus_postcode", "1000")
			.containsEntry("cus_country", "Bangladesh")
			.containsEntry("shipping_method", "NO")
			.containsEntry("product_profile", "physical-goods")
			.containsEntry("value_a", "BB-2026-000042");
		assertThat(sent.get("success_url")).isEqualTo(sent.get("fail_url")).isEqualTo(sent.get("cancel_url"))
			.endsWith("/return");
		assertThat(sent).containsKeys("product_name", "product_category");
		assertThat(session.checkoutUrl().toString()).startsWith(stub.baseUrl() + "/gwprocess/v4/gw.php");

		gateway.createSession(request(UUID.randomUUID(), "nusrat@example.com"));
		assertThat(stub.sessionRequests.get(1)).containsEntry("cus_email", "nusrat@example.com");
	}

	@Test
	void tranIdsAreStableShortAndPlain() {
		UUID paymentId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
		String longest = SslCommerzGateway.tranId(paymentId);
		assertThat(longest).hasSizeLessThanOrEqualTo(30).matches("BB[0-9a-z]+");
		assertThat(SslCommerzGateway.tranId(paymentId)).isEqualTo(longest);
		assertThat(SslCommerzGateway.tranId(UUID.randomUUID())).isNotEqualTo(longest);
	}

	@Test
	void aRefusedSessionFailsClosed() {
		SslCommerzProperties wrong = new SslCommerzProperties(true, URI.create(stub.baseUrl()), "bakedtest01",
				"not-the-password", URI.create("https://api.example.test/ipn"), "orders@bakedandbrowned.test", "Dhaka",
				"1000", "Bangladesh", Duration.ofSeconds(2), Duration.ofSeconds(2));
		SslCommerzGateway misconfigured = new SslCommerzGateway(wrong, JsonMapper.builder().build());

		assertThatThrownBy(() -> misconfigured.createSession(request(UUID.randomUUID(), null)))
			.isInstanceOf(PaymentVerificationException.class)
			.hasMessageContaining("Store Credential Error")
			.hasMessageNotContaining("not-the-password");
		assertThatThrownBy(() -> misconfigured.queryStatus("BBunknown")).isInstanceOf(PaymentVerificationException.class);
	}

	@Test
	void anUnreachableProviderFailsWithoutLeakingTheStorePassword() {
		SslCommerzGateway offline = new SslCommerzGateway(SslCommerzStub.properties("http://127.0.0.1:1"),
				JsonMapper.builder().build());

		assertThatThrownBy(() -> offline.queryStatus("BBabc")).isInstanceOf(PaymentVerificationException.class)
			.hasMessageNotContaining(SslCommerzStub.STORE_PASSWORD);
		assertThatThrownBy(() -> offline.createSession(request(UUID.randomUUID(), null)))
			.isInstanceOf(PaymentVerificationException.class)
			.hasMessageNotContaining(SslCommerzStub.STORE_PASSWORD);
	}

	@Test
	void theAmountIsReadInTheCurrencyWeAskedFor() {
		String tranId = gateway.createSession(request(UUID.randomUUID(), null)).sessionId();
		stub.settle(tranId, GatewayPaymentStatus.SUCCEEDED);
		stub.charge(tranId, "13.10", "USD");

		VerifiedPaymentResult result = gateway.verify(stub.ipn(tranId, GatewayPaymentStatus.SUCCEEDED));

		assertThat(result.status()).isEqualTo(GatewayPaymentStatus.SUCCEEDED);
		assertThat(result.amount()).isEqualTo(Money.of("13.10"));
		assertThat(result.currency()).isEqualTo("USD");
		assertThat(result.confirms("BB-2026-000042", Money.of("1310.00"))).isFalse();
	}

	@Test
	void riskLevelOneIsReportedAsHighRisk() {
		String tranId = gateway.createSession(request(UUID.randomUUID(), null)).sessionId();
		stub.settle(tranId, GatewayPaymentStatus.SUCCEEDED);

		assertThat(gateway.verify(stub.ipn(tranId, GatewayPaymentStatus.SUCCEEDED)).highRisk()).isFalse();
		stub.flagHighRisk(tranId);
		VerifiedPaymentResult flagged = gateway.queryStatus(tranId);
		assertThat(flagged.highRisk()).isTrue();
		assertThat(flagged.confirms("BB-2026-000042", Money.of("1310.00"))).isTrue();
	}

	@Test
	void aFailureNoticeIsCheckedWithTheProviderNotBelieved() {
		String tranId = gateway.createSession(request(UUID.randomUUID(), null)).sessionId();
		stub.settle(tranId, GatewayPaymentStatus.SUCCEEDED);

		// A notice claiming failure for a transaction that succeeded resolves to the success.
		VerifiedPaymentResult result = gateway.verify(stub.ipn(tranId, GatewayPaymentStatus.FAILED));
		assertThat(result.status()).isEqualTo(GatewayPaymentStatus.SUCCEEDED);
		// The status query and the validated notice describe the same provider state.
		assertThat(gateway.queryStatus(tranId).eventId()).isEqualTo(result.eventId());
	}

	@Test
	void malformedNoticesAreRejected() {
		for (String body : new String[] { "", "status=VALID", "tran_id=%ZZ&status=VALID" }) {
			assertThatThrownBy(() -> gateway
				.verify(new ProviderCallback(SslCommerzGateway.PROVIDER, "application/x-www-form-urlencoded", Map.of(), body)))
				.isInstanceOf(PaymentVerificationException.class);
		}
	}

	@Test
	void settingsAreCheckedAtStartup() {
		assertThatThrownBy(() -> SslCommerzStub.properties("http://sandbox.sslcommerz.com").settings())
			.hasMessageContaining("https");
		SslCommerzProperties noPassword = new SslCommerzProperties(true, URI.create("https://sandbox.sslcommerz.com"),
				"bakedtest01", " ", URI.create("https://api.example.test/ipn"), "orders@bakedandbrowned.test", "Dhaka",
				"1000", "Bangladesh", Duration.ofSeconds(2), Duration.ofSeconds(2));
		assertThatThrownBy(noPassword::settings).hasMessageContaining("store-password");
		assertThat(SslCommerzStub.properties("https://sandbox.sslcommerz.com/").settings().toString())
			.doesNotContain(SslCommerzStub.STORE_PASSWORD);
	}

}
