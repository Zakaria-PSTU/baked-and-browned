package com.bakedandbrowned.scheduling.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.common.time.BusinessTime;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.scheduling.dto.SlotDtos.AdminSlotResponse;
import com.bakedandbrowned.scheduling.dto.SlotDtos.SlotResponse;
import com.bakedandbrowned.scheduling.dto.SlotDtos.SlotUpdate;
import com.bakedandbrowned.scheduling.dto.SlotDtos.SlotWrite;
import com.bakedandbrowned.scheduling.entity.DeliverySlot;
import com.bakedandbrowned.scheduling.repository.SlotRepository;

/**
 * Delivery slots: what customers can book on a Dhaka calendar date, and admin management
 * (audited). Dates are Asia/Dhaka days, resolved with the business clock.
 */
@Service
public class SlotService {

	public static final String SLOT_NOT_FOUND = "SLOT_NOT_FOUND";

	public static final String SLOT_EXISTS = "SLOT_EXISTS";

	public static final String INVALID_SLOT = "INVALID_SLOT";

	public static final String SLOT_IN_PAST = "SLOT_IN_PAST";

	public static final String INVALID_RANGE = "INVALID_RANGE";

	public static final String CAPACITY_BELOW_RESERVED = "CAPACITY_BELOW_RESERVED";

	/** Contract: {@code to} at most 62 days after {@code from}. */
	static final int MAX_RANGE_DAYS = 62;

	private final SlotRepository slots;

	private final AuditLog audit;

	private final Clock clock;

	public SlotService(SlotRepository slots, AuditLog audit, Clock clock) {
		this.slots = slots;
		this.audit = audit;
		this.clock = clock;
	}

	/** Active, not yet started slots with capacity left, starting on {@code date} in Dhaka. */
	@Transactional(readOnly = true)
	public List<SlotResponse> bookable(LocalDate date) {
		return slots.findBookable(startOf(date), startOf(date.plusDays(1)), clock.instant())
			.stream()
			.map(SlotResponse::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<AdminSlotResponse> between(LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			throw ApiException.unprocessable(INVALID_RANGE, "to must not be before from.");
		}
		if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
			throw ApiException.unprocessable(INVALID_RANGE, "to may be at most " + MAX_RANGE_DAYS + " days after from.");
		}
		return slots.findStartingBetween(startOf(from), startOf(to.plusDays(1)))
			.stream()
			.map(AdminSlotResponse::from)
			.toList();
	}

	@Transactional
	public AdminSlotResponse create(UUID actor, SlotWrite write) {
		if (!write.endAt().isAfter(write.startAt())) {
			throw ApiException.unprocessable(INVALID_SLOT, "endAt must be after startAt.");
		}
		if (!write.startAt().isAfter(clock.instant())) {
			throw ApiException.unprocessable(SLOT_IN_PAST, "startAt must be in the future.");
		}
		if (slots.existsByStartAtAndEndAt(write.startAt(), write.endAt())) {
			throw exists();
		}
		DeliverySlot slot;
		try {
			slot = slots.saveAndFlush(
					new DeliverySlot(write.startAt(), write.endAt(), write.capacityUnits(), write.activeOrDefault()));
		}
		catch (DataIntegrityViolationException ex) {
			throw exists();
		}
		audit.admin(actor, "DELIVERY_SLOT_CREATED", "delivery_slot", slot.getId(), null, snapshot(slot));
		return AdminSlotResponse.from(slot);
	}

	/** Changes capacity and/or opens or closes the slot; closing keeps existing reservations. */
	@Transactional
	public AdminSlotResponse update(UUID actor, UUID id, SlotUpdate update) {
		DeliverySlot before = slots.findById(id)
			.orElseThrow(() -> ApiException.notFound(SLOT_NOT_FOUND, "Delivery slot not found."));
		Map<String, Object> was = snapshot(before);
		if (slots.updateCapacity(id, update.capacityUnits(), update.active()) == 0) {
			throw ApiException.conflict(CAPACITY_BELOW_RESERVED,
					"Capacity cannot be lower than the units already reserved.");
		}
		DeliverySlot after = slots.findById(id).orElseThrow();
		Map<String, Object> now = snapshot(after);
		if (!was.equals(now)) {
			audit.admin(actor, "DELIVERY_SLOT_UPDATED", "delivery_slot", id, null, Map.of("from", was, "to", now));
		}
		return AdminSlotResponse.from(after);
	}

	private static Instant startOf(LocalDate date) {
		return date.atStartOfDay(BusinessTime.ZONE).toInstant();
	}

	private static Map<String, Object> snapshot(DeliverySlot s) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("startAt", s.getStartAt().toString());
		m.put("endAt", s.getEndAt().toString());
		m.put("capacityUnits", s.getCapacityUnits());
		m.put("active", s.isActive());
		return m;
	}

	private static ApiException exists() {
		return ApiException.conflict(SLOT_EXISTS, "A slot with the same start and end already exists.");
	}

}
