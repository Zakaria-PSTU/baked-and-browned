package com.bakedandbrowned.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class OutboxPropertiesTest {

	private final OutboxProperties properties = new OutboxProperties(50, Duration.ofMinutes(5), Duration.ofSeconds(30),
			Duration.ofHours(1));

	@Test
	void backoffDoublesPerAttemptUpToTheCap() {
		assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(30));
		assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofSeconds(60));
		assertThat(properties.backoffAfter(5)).isEqualTo(Duration.ofMinutes(8));
		assertThat(properties.backoffAfter(8)).isEqualTo(Duration.ofHours(1));
		assertThat(properties.backoffAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(1));
	}

	@Test
	void rejectsNonsenseSettings() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OutboxProperties(0, Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofHours(1)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OutboxProperties(1, Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(1)));
	}

}
