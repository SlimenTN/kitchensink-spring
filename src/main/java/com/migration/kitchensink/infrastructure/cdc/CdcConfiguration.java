package com.migration.kitchensink.infrastructure.cdc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The engine only runs when migration.cdc.enabled=true (tests that don't need MySQL turn it off)
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("migration.cdc.enabled")
@EnableConfigurationProperties(CdcProperties.class)
public class CdcConfiguration {

	@Bean
	DebeziumEngineRunner debeziumEngineRunner(CdcProperties properties, MemberChangeHandler memberChangeHandler) {
		return new DebeziumEngineRunner(properties, memberChangeHandler);
	}

}
