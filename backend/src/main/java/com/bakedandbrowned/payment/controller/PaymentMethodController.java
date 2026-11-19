package com.bakedandbrowned.payment.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.ordering.dto.OrderDtos.PaymentMethod;
import com.bakedandbrowned.payment.dto.PaymentDtos.PaymentMethods;
import com.bakedandbrowned.payment.gateway.PaymentGateways;
import com.bakedandbrowned.payment.service.ManualPaymentProperties;

/**
 * Contract {@code listPaymentMethods}: which payment methods checkout offers in this deployment.
 * Both inputs are fixed at startup, so the answer is computed once.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentMethodController {

	private final PaymentMethods methods;

	PaymentMethodController(PaymentGateways gateways, ManualPaymentProperties manual) {
		List<PaymentMethod> available = new ArrayList<>();
		available.add(PaymentMethod.COD);
		if (!manual.accounts().isEmpty()) {
			available.add(PaymentMethod.MANUAL);
		}
		if (gateways.active().isPresent()) {
			available.add(PaymentMethod.ONLINE);
		}
		this.methods = new PaymentMethods(List.copyOf(available));
	}

	@GetMapping("/methods")
	PaymentMethods methods() {
		return methods;
	}

}
