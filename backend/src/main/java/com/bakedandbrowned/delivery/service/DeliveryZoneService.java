package com.bakedandbrowned.delivery.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.delivery.dto.DeliveryZoneDtos.AdminDeliveryZoneResponse;
import com.bakedandbrowned.delivery.dto.DeliveryZoneDtos.DeliveryZoneResponse;
import com.bakedandbrowned.delivery.dto.DeliveryZoneDtos.DeliveryZoneWrite;
import com.bakedandbrowned.delivery.entity.DeliveryZone;
import com.bakedandbrowned.delivery.repository.DeliveryZoneRepository;
import com.bakedandbrowned.delivery.service.ZoneEligibility.EligibleZone;

/**
 * Delivery zones (ADR-0010): the public list of selectable zones, admin management (audited), and
 * the eligibility check other modules call at quote and checkout time.
 */
@Service
public class DeliveryZoneService {

	public static final String ZONE_NOT_FOUND = "ZONE_NOT_FOUND";

	public static final String ZONE_EXISTS = "ZONE_EXISTS";

	public static final String ZONE_FEE_REQUIRED = "ZONE_FEE_REQUIRED";

	private final DeliveryZoneRepository zones;

	private final AuditLog audit;

	public DeliveryZoneService(DeliveryZoneRepository zones, AuditLog audit) {
		this.zones = zones;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<DeliveryZoneResponse> selectable() {
		return zones.findSelectable().stream().map(DeliveryZoneResponse::from).toList();
	}

	/**
	 * The zone for {@code code} ({@code DeliveryAddress.area}) if an order with {@code subtotal} may be
	 * delivered there.
	 * @throws ApiException 422 {@link ZoneEligibility#ZONE_NOT_AVAILABLE} or
	 * {@link ZoneEligibility#BELOW_MINIMUM_ORDER}
	 */
	@Transactional(readOnly = true)
	public EligibleZone eligibleZone(@Nullable String code, Money subtotal) {
		DeliveryZone zone = code == null ? null : zones.findByCode(code.strip()).orElse(null);
		return ZoneEligibility.check(zone, subtotal);
	}

	@Transactional(readOnly = true)
	public List<AdminDeliveryZoneResponse> all() {
		return zones.findAllOrdered().stream().map(AdminDeliveryZoneResponse::from).toList();
	}

	@Transactional
	public AdminDeliveryZoneResponse create(UUID actor, DeliveryZoneWrite write) {
		DeliveryZone.Details details = details(write);
		if (zones.existsByCodeOrName(details.code(), details.name())) {
			throw exists();
		}
		DeliveryZone zone = save(new DeliveryZone(details));
		audit.admin(actor, "DELIVERY_ZONE_CREATED", "delivery_zone", zone.getId(), null, snapshot(zone));
		return AdminDeliveryZoneResponse.from(zone);
	}

	@Transactional
	public AdminDeliveryZoneResponse update(UUID actor, UUID id, DeliveryZoneWrite write) {
		DeliveryZone zone = zones.findById(id)
			.orElseThrow(() -> ApiException.notFound(ZONE_NOT_FOUND, "Delivery zone not found."));
		DeliveryZone.Details details = details(write);
		if (zones.codeOrNameTakenByOther(details.code(), details.name(), id)) {
			throw exists();
		}
		Map<String, Object> before = snapshot(zone);
		zone.replace(details);
		save(zone);
		Map<String, Object> after = snapshot(zone);
		if (!before.equals(after)) {
			// Placed orders keep their own fee snapshot; this only affects new quotes and orders.
			audit.admin(actor, "DELIVERY_ZONE_UPDATED", "delivery_zone", id, null, Map.of("from", before, "to", after));
		}
		return AdminDeliveryZoneResponse.from(zone);
	}

	private static DeliveryZone.Details details(DeliveryZoneWrite write) {
		if (write.active() && write.deliveryFee() == null) {
			throw ApiException.unprocessable(ZONE_FEE_REQUIRED, "deliveryFee: required to activate a zone.");
		}
		return new DeliveryZone.Details(write.code(), write.name().strip(), write.deliveryFee(), write.minOrderAmount(),
				write.active(), write.sortOrderOrDefault());
	}

	private DeliveryZone save(DeliveryZone zone) {
		try {
			return zones.saveAndFlush(zone);
		}
		catch (DataIntegrityViolationException ex) {
			throw exists();
		}
	}

	private static Map<String, Object> snapshot(DeliveryZone z) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("code", z.getCode());
		s.put("name", z.getName());
		s.put("deliveryFee", z.getDeliveryFee() == null ? null : z.getDeliveryFee().amount().toPlainString());
		s.put("minOrderAmount", z.getMinOrderAmount() == null ? null : z.getMinOrderAmount().amount().toPlainString());
		s.put("active", z.isActive());
		s.put("sortOrder", z.getSortOrder());
		return s;
	}

	private static ApiException exists() {
		return ApiException.conflict(ZONE_EXISTS, "Another delivery zone already uses this code or name.");
	}

}
