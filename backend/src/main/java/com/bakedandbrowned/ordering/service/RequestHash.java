package com.bakedandbrowned.ordering.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.bakedandbrowned.ordering.dto.CheckoutDtos.CartLine;
import com.bakedandbrowned.ordering.dto.OrderDtos.PlaceOrderRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * Fingerprint of an order request for idempotency ({@code orders.request_hash}): SHA-256 over a
 * canonical form of everything that defines the order (slot, delivery details, lines, payment
 * method), not the idempotency key itself. Canonical means: maps sorted by key, selected option ids
 * sorted, lines kept in the order sent. Same cart sent twice, same hash; any real change, a new one.
 */
final class RequestHash {

	private RequestHash() {
	}

	static String of(PlaceOrderRequest request, JsonMapper json) {
		Map<String, Object> canonical = new TreeMap<>();
		canonical.put("deliverySlotId", request.deliverySlotId().toString());
		canonical.put("paymentMethod", request.paymentMethod().name());
		Map<String, Object> delivery = new TreeMap<>();
		delivery.put("name", request.delivery().name());
		delivery.put("phone", request.delivery().phone().value());
		delivery.put("area", request.delivery().area());
		delivery.put("address", request.delivery().address());
		delivery.put("notes", request.delivery().notes() == null ? "" : request.delivery().notes());
		canonical.put("delivery", delivery);
		List<Object> lines = new ArrayList<>();
		for (CartLine line : request.items()) {
			Map<String, Object> l = new TreeMap<>();
			l.put("productId", line.productId().toString());
			l.put("quantity", line.quantity());
			l.put("selectedOptionIds", line.selectedOptionIds().stream().map(UUID::toString).sorted().toList());
			Map<String, String> texts = new TreeMap<>();
			line.customTexts().forEach((group, text) -> texts.put(group.toString(), text));
			l.put("customTexts", texts);
			lines.add(l);
		}
		canonical.put("items", lines);
		return sha256(json.writeValueAsString(canonical));
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
