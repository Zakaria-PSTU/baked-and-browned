package com.bakedandbrowned.notification.sms.sslwireless;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

/**
 * Registers {@link SslWirelessSmsSender} only with {@code app.notifications.sms.ssl-wireless.enabled=true}
 * (ADR-0027); its settings are checked at startup, so a missing token fails fast instead of at the
 * first notice. Without it, a {@code prod} context has no SmsSender and refuses to start (ADR-0016).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.notifications.sms.ssl-wireless", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SslWirelessProperties.class)
class SslWirelessConfiguration {

	@Bean
	SslWirelessSmsSender sslWirelessSmsSender(SslWirelessProperties properties, JsonMapper json) {
		return new SslWirelessSmsSender(properties, json);
	}

}
