package com.bakedandbrowned.payment.gateway.sslcommerz;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;

import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.PaymentGateway;
import com.bakedandbrowned.payment.gateway.PaymentGatewayContract;
import com.bakedandbrowned.payment.gateway.ProviderCallback;

import tools.jackson.databind.json.JsonMapper;

/** The shared gateway contract, against {@link SslCommerzStub}; rerun it against the sandbox before going live. */
class SslCommerzGatewayContractTest extends PaymentGatewayContract {

	private final SslCommerzStub stub;

	private final SslCommerzGateway gateway;

	SslCommerzGatewayContractTest() throws IOException {
		stub = new SslCommerzStub();
		gateway = new SslCommerzGateway(stub.properties(), JsonMapper.builder().build());
	}

	@AfterEach
	void stop() {
		stub.close();
	}

	@Override
	protected PaymentGateway gateway() {
		return gateway;
	}

	@Override
	protected void settle(String sessionId, GatewayPaymentStatus outcome) {
		stub.settle(sessionId, outcome);
	}

	@Override
	protected ProviderCallback authenticCallback(String sessionId, GatewayPaymentStatus claimedStatus) {
		return stub.ipn(sessionId, claimedStatus);
	}

	/**
	 * An IPN carries no signature the docs let us check; what it cannot forge is the validation id,
	 * which only SSLCommerz resolves, or the transaction that id belongs to.
	 */
	@Override
	protected List<ProviderCallback> forgeries(ProviderCallback authentic, String sessionId) {
		String body = authentic.body();
		return List.of(
				new ProviderCallback(authentic.provider(), authentic.contentType(), authentic.headers(),
						body.replaceAll("&val_id=[^&]*", "") + "&val_id=forged0001"),
				new ProviderCallback(authentic.provider(), authentic.contentType(), authentic.headers(),
						body.replace("tran_id=" + sessionId, "tran_id=" + sessionId + "x")));
	}

}
