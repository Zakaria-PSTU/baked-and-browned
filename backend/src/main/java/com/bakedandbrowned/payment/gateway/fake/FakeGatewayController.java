package com.bakedandbrowned.payment.gateway.fake;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.fake.FakePaymentGateway.HostedPage;
import com.bakedandbrowned.payment.service.GatewayResults;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * The fake gateway's "hosted page" for local development: the web app's {@code /dev/fake-gateway}
 * screen reads a session and finishes it the way a customer would on a real provider's page. It exists
 * only with {@link FakePaymentGateway} (dev and test profiles, never prod) and is deliberately not in
 * the API contract.
 * <p>
 * Finishing plays the provider's part: the outcome is recorded on the provider side, then the signed
 * callback goes through the same verification and {@link GatewayResults} as a real webhook, unless the
 * caller asks for it to be lost, which leaves the payment to reconciliation. The browser is then sent
 * to the session's return URL, as a real provider does.
 */
@RestController
@Profile({ "dev", "test" })
@RequestMapping("/api/v1/dev/fake-gateway/sessions/{sessionId}")
class FakeGatewayController {

	static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";

	static final String SESSION_FINISHED = "SESSION_FINISHED";

	static final String INVALID_OUTCOME = "INVALID_OUTCOME";

	private static final Logger log = LoggerFactory.getLogger(FakeGatewayController.class);

	private final FakePaymentGateway gateway;

	private final GatewayResults results;

	FakeGatewayController(FakePaymentGateway gateway, GatewayResults results) {
		this.gateway = gateway;
		this.results = results;
	}

	@GetMapping
	FakeSession session(@PathVariable("sessionId") String sessionId) {
		return FakeSession.of(page(sessionId));
	}

	@PostMapping("/finish")
	FakeSession finish(@PathVariable("sessionId") String sessionId, @Valid @RequestBody FinishRequest request) {
		if (request.outcome() == GatewayPaymentStatus.PENDING) {
			throw ApiException.unprocessable(INVALID_OUTCOME, "Choose how the payment ends.");
		}
		page(sessionId);
		if (!gateway.finish(sessionId, request.outcome())) {
			throw ApiException.conflict(SESSION_FINISHED, "This payment page has already been finished.");
		}
		if (!Boolean.FALSE.equals(request.notifyShop())) {
			GatewayResults.Outcome outcome = results
				.apply(gateway.verify(gateway.callbackFor(sessionId, request.outcome())));
			log.info("Fake gateway session {} finished {}: {}", sessionId, request.outcome(), outcome);
		}
		else {
			log.info("Fake gateway session {} finished {} with its callback lost", sessionId, request.outcome());
		}
		return FakeSession.of(page(sessionId));
	}

	private HostedPage page(String sessionId) {
		return gateway.hostedPage(sessionId)
			.orElseThrow(() -> ApiException.notFound(SESSION_NOT_FOUND, "There is no such test payment."));
	}

	/** One test payment as its page shows it; {@code returnUrl} is where the browser goes next. */
	record FakeSession(String sessionId, String orderNumber, Money amount, String currency,
			GatewayPaymentStatus status, URI returnUrl) {

		static FakeSession of(HostedPage page) {
			return new FakeSession(page.sessionId(), page.orderReference(), page.amount(), "BDT", page.status(),
					page.returnUrl());
		}

	}

	/** {@code notifyShop}: false loses the provider's callback, as when a webhook never arrives. */
	record FinishRequest(@NotNull GatewayPaymentStatus outcome, @Nullable Boolean notifyShop) {
	}

}
