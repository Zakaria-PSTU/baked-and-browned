package com.bakedandbrowned.payment.gateway.sslcommerz;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

/**
 * Registers {@link SslCommerzGateway} only with {@code app.payments.sslcommerz.enabled=true}; its
 * settings are checked at startup, so a missing credential fails fast instead of at checkout.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.payments.sslcommerz", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SslCommerzProperties.class)
class SslCommerzConfiguration {

	@Bean
	SslCommerzGateway sslCommerzGateway(SslCommerzProperties properties, JsonMapper json) {
		return new SslCommerzGateway(properties, json);
	}

}
