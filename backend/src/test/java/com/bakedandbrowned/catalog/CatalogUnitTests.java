package com.bakedandbrowned.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bakedandbrowned.catalog.repository.ProductSpecificationsAccess;
import com.bakedandbrowned.common.validation.HttpsUrl;

class CatalogUnitTests {

	private final HttpsUrl.Validator https = new HttpsUrl.Validator();

	@Test
	void searchTermsAreEscapedForLike() {
		assertThat(ProductSpecificationsAccess.containsPattern("Fudge")).isEqualTo("%fudge%");
		assertThat(ProductSpecificationsAccess.containsPattern("100%_\\")).isEqualTo("%100\\%\\_\\\\%");
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://cdn.example.com/a.jpg", "HTTPS://cdn.example.com/a.jpg?w=400" })
	void acceptsHttpsUrls(String url) {
		assertThat(https.isValid(url, null)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://cdn.example.com/a.jpg", "javascript:alert(1)", "//cdn.example.com/a.jpg",
			"https:///nohost", "https://user:pw@cdn.example.com/a.jpg", "data:image/png;base64,AA", "not a url", "" })
	void rejectsEverythingElse(String url) {
		assertThat(https.isValid(url, null)).isFalse();
	}

	@Test
	void nullIsLeftToNotNull() {
		assertThat(https.isValid(null, null)).isTrue();
	}

}
