package com.migration.kitchensink;

import org.testcontainers.mysql.MySQLContainer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

// Throwaway legacy MySQL database (Member + Member_SEQ, John Smith seeded); @ServiceConnection replaces spring.datasource.*
@TestConfiguration(proxyBeanMethods = false)
public class LegacyMySqlTestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MySQLContainer legacyMySqlContainer() {
		return new MySQLContainer("mysql:8.0").withDatabaseName("kitchensink")
			.withInitScript("cdc/legacy-member-schema.sql");
	}

}
