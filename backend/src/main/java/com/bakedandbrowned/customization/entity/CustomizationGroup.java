package com.bakedandbrowned.customization.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of {@code customization_groups}. Min/max selections are group-level and apply to every
 * product using the group; whether a selection is required is per product.
 */
@Entity
@Table(name = "customization_groups")
public class CustomizationGroup {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 80)
	private String code;

	@Column(nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "selection_type", nullable = false, length = 10)
	private SelectionType selectionType;

	@Column(name = "min_selections", nullable = false)
	private int minSelections;

	@Column(name = "max_selections", nullable = false)
	private int maxSelections;

	protected CustomizationGroup() {
	}

	public CustomizationGroup(String code, String name, SelectionType selectionType, int minSelections,
			int maxSelections) {
		apply(code, name, selectionType, minSelections, maxSelections);
	}

	/** Full replacement of the writable fields. */
	public void replace(String code, String name, SelectionType selectionType, int minSelections, int maxSelections) {
		apply(code, name, selectionType, minSelections, maxSelections);
	}

	// Private, so the constructor never calls an overridable method (Hibernate proxies subclass entities).
	private void apply(String code, String name, SelectionType selectionType, int minSelections, int maxSelections) {
		this.code = code;
		this.name = name;
		this.selectionType = selectionType;
		this.minSelections = minSelections;
		this.maxSelections = maxSelections;
	}

	public UUID getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public SelectionType getSelectionType() {
		return selectionType;
	}

	public int getMinSelections() {
		return minSelections;
	}

	public int getMaxSelections() {
		return maxSelections;
	}

}
