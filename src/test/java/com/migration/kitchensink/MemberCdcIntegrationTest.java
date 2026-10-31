package com.migration.kitchensink;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Legacy MySQL -> embedded Debezium -> domain -> MongoDB, with real MySQL and MongoDB containers
@SpringBootTest
@Testcontainers
@Import(TestcontainersConfiguration.class)
class MemberCdcIntegrationTest {

	private static final Duration CDC_TIMEOUT = Duration.ofSeconds(60);

	// binlog is enabled in ROW format by default in MySQL 8
	@Container
	static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0").withDatabaseName("kitchensink")
		.withInitScript("cdc/legacy-member-schema.sql");

	@Autowired
	private MongoTemplate mongoTemplate;

	@DynamicPropertySource
	static void cdcProperties(DynamicPropertyRegistry registry) {
		registry.add("migration.cdc.enabled", () -> "true");
		registry.add("migration.cdc.mysql.host", MYSQL::getHost);
		registry.add("migration.cdc.mysql.port", () -> MYSQL.getMappedPort(3306));
		// root: Debezium needs replication privileges (the container's root password is the configured password)
		registry.add("migration.cdc.mysql.user", () -> "root");
		registry.add("migration.cdc.mysql.password", MYSQL::getPassword);
		registry.add("migration.cdc.mysql.database", () -> "kitchensink");
		registry.add("migration.cdc.mysql.table", () -> "Member");
		registry.add("migration.cdc.mysql.server-id", () -> "5499");
		registry.add("migration.cdc.state-directory", () -> tempDirectory().toString());
	}

	@Test
	void initialSnapshotCopiesExistingRows() {
		await().atMost(CDC_TIMEOUT)
			.untilAsserted(() -> assertThat(member(0L))
				.isEqualTo(document(0L, "John Smith", "john.smith@mailinator.com", "2125551212")));
	}

	@Test
	void insertedRowAppearsInMongoDbWithExactlyTheMemberFields() {
		sql("INSERT INTO Member VALUES (10, 'jane.doe@example.com', 'Jane Doe', '2125551234')");

		await().atMost(CDC_TIMEOUT)
			.untilAsserted(() -> assertThat(member(10L))
				.isEqualTo(document(10L, "Jane Doe", "jane.doe@example.com", "2125551234")));
		// no extra field such as Spring Data's _class: the production collection validator rejects them
		assertThat(member(10L).keySet()).containsExactlyInAnyOrder("_id", "name", "email", "phoneNumber");
		assertThat(member(10L).get("_id")).isInstanceOf(Long.class);
	}

	@Test
	void updatedRowIsUpdatedInMongoDb() {
		sql("INSERT INTO Member VALUES (11, 'max@example.com', 'Max Muster', '2125551111')");
		sql("UPDATE Member SET name = 'Max Mustermann', phone_number = '2125552222' WHERE id = 11");

		await().atMost(CDC_TIMEOUT)
			.untilAsserted(() -> assertThat(member(11L))
				.isEqualTo(document(11L, "Max Mustermann", "max@example.com", "2125552222")));
	}

	@Test
	void deletedRowIsRemovedFromMongoDb() {
		sql("INSERT INTO Member VALUES (12, 'gone@example.com', 'Gone Soon', '2125553333')");
		await().atMost(CDC_TIMEOUT).untilAsserted(() -> assertThat(member(12L)).isNotNull());

		sql("DELETE FROM Member WHERE id = 12");

		await().atMost(CDC_TIMEOUT).untilAsserted(() -> assertThat(member(12L)).isNull());
	}

	private Document member(long id) {
		return mongoTemplate.getCollection("members").find(eq("_id", id)).first();
	}

	private static Document document(long id, String name, String email, String phoneNumber) {
		return new Document("_id", id).append("name", name).append("email", email).append("phoneNumber", phoneNumber);
	}

	private static void sql(String statement) {
		try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
				Statement jdbc = connection.createStatement()) {
			jdbc.executeUpdate(statement);
		}
		catch (SQLException e) {
			throw new IllegalStateException("MySQL statement failed: " + statement, e);
		}
	}

	private static java.nio.file.Path tempDirectory() {
		try {
			return Files.createTempDirectory("kitchensink-cdc-test");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
