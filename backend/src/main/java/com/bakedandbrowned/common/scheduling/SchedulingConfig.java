package com.bakedandbrowned.common.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} jobs (outbox dispatch, refresh-token cleanup; hold expiry from Phase 2)
 * unless {@code app.scheduling.enabled=false}. Integration tests disable it and run jobs directly,
 * so background work never races assertions.
 * <p>
 * Jobs must be safe to run concurrently with themselves (another instance, or an overlapping run):
 * claim work with {@code FOR UPDATE SKIP LOCKED} or make each statement idempotent.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

}
