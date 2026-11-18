package com.bakedandbrowned.scheduling.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bakedandbrowned.scheduling.entity.DeliverySlot;

public interface SlotRepository extends JpaRepository<DeliverySlot, UUID> {

	/** Bookable slots starting in {@code [from, to)} after {@code now}: active with capacity left. */
	@Query("""
			select s from DeliverySlot s
			where s.startAt >= :from and s.startAt < :to and s.startAt > :now
			  and s.active = true and s.reservedUnits < s.capacityUnits
			order by s.startAt, s.endAt""")
	List<DeliverySlot> findBookable(@Param("from") Instant from, @Param("to") Instant to, @Param("now") Instant now);

	@Query("select s from DeliverySlot s where s.startAt >= :from and s.startAt < :to order by s.startAt, s.endAt")
	List<DeliverySlot> findStartingBetween(@Param("from") Instant from, @Param("to") Instant to);

	boolean existsByStartAtAndEndAt(Instant startAt, Instant endAt);

	/**
	 * Atomic capacity change: succeeds (returns 1) only if the new capacity still covers what is
	 * reserved at that moment, so it can never race a concurrent reservation into over-allocation.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update DeliverySlot s set s.capacityUnits = :capacity, s.active = :active
			where s.id = :id and s.reservedUnits <= :capacity""")
	int updateCapacity(@Param("id") UUID id, @Param("capacity") int capacity, @Param("active") boolean active);

}
