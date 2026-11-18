package com.bakedandbrowned.catalog.repository;

/** Test-only bridge to the package-private LIKE escaping helper. */
public final class ProductSpecificationsAccess {

	private ProductSpecificationsAccess() {
	}

	public static String containsPattern(String query) {
		return ProductSpecifications.containsPattern(query);
	}

}
