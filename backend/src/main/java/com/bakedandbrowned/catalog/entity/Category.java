package com.bakedandbrowned.catalog.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of {@code categories}. Inactive categories, and their products, are hidden from the public catalog. */
@Entity
@Table(name = "categories")
public class Category {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 120)
	private String slug;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(nullable = false)
	private boolean active;

	protected Category() {
	}

	public Category(String name, String slug, int sortOrder, boolean active) {
		apply(name, slug, sortOrder, active);
	}

	/** Full replacement of the writable fields (contract {@code CategoryWrite}). */
	public void replace(String name, String slug, int sortOrder, boolean active) {
		apply(name, slug, sortOrder, active);
	}

	// Private, so the constructor never calls an overridable method (Hibernate proxies subclass entities).
	private void apply(String name, String slug, int sortOrder, boolean active) {
		this.name = name;
		this.slug = slug;
		this.sortOrder = sortOrder;
		this.active = active;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public boolean isActive() {
		return active;
	}

}
