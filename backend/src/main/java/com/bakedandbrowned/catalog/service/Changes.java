package com.bakedandbrowned.catalog.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** Collects {@code field -> {from, to}} for audit details; unchanged fields are left out. */
final class Changes {

	private final Map<String, Map<String, @Nullable Object>> changes = new LinkedHashMap<>();

	Changes field(String name, @Nullable Object from, @Nullable Object to) {
		if (!Objects.equals(from, to)) {
			Map<String, @Nullable Object> change = new LinkedHashMap<>();
			change.put("from", from);
			change.put("to", to);
			changes.put(name, change);
		}
		return this;
	}

	/** For long free text: records only that it changed, not the content. */
	Changes flag(String name, @Nullable Object from, @Nullable Object to) {
		if (!Objects.equals(from, to)) {
			changes.put(name, Map.of("changed", true));
		}
		return this;
	}

	boolean isEmpty() {
		return changes.isEmpty();
	}

	Map<String, Object> asDetails() {
		return Map.of("changes", changes);
	}

}
