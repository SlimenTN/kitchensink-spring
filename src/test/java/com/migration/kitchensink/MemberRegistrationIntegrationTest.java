package com.migration.kitchensink;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

// POST /rest/members through every layer: HTTP -> rest -> domaine -> legacy MySQL. CDC is off: MongoDB must not be
// written by the registration itself (no dual write, CDC copies members later).
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "migration.cdc.enabled=false")
@Import({ LegacyMySqlTestcontainersConfiguration.class, TestcontainersConfiguration.class })
class MemberRegistrationIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private MongoTemplate mongoTemplate;

	@BeforeEach
	void resetDatabases() {
		jdbcClient.sql("DELETE FROM Member WHERE id <> 0").update();
		jdbcClient.sql("UPDATE Member_SEQ SET next_val = 151").update();
		mongoTemplate.getCollection("members").deleteMany(new Document());
	}

	@Test
	void validMemberIsStoredInTheLegacyDatabaseOnly() throws Exception {
		HttpResponse<String> response = post("""
				{"name": "Jane Doe", "email": "jane.doe@example.com", "phoneNumber": "2125551234"}""");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEmpty();
		assertThat(response.headers().firstValue("Content-Type")).isEmpty();
		// id from Member_SEQ (151), not max(id) + 1 (1)
		assertThat(jdbcClient.sql("SELECT CONCAT(id, '|', name, '|', email, '|', phone_number) FROM Member WHERE email = ?")
			.param("jane.doe@example.com")
			.query(String.class)
			.single()).isEqualTo("151|Jane Doe|jane.doe@example.com|2125551234");
		assertThat(jdbcClient.sql("SELECT next_val FROM Member_SEQ").query(Long.class).single()).isEqualTo(152L);
		assertThat(mongoTemplate.getCollection("members").countDocuments()).as("no dual write to MongoDB").isZero();
	}

	@Test
	void emailAlreadyTakenIsAnsweredWith409() throws Exception {
		HttpResponse<String> response = post("""
				{"name": "Someone Else", "email": "john.smith@mailinator.com", "phoneNumber": "2125551234"}""");

		assertThat(response.statusCode()).isEqualTo(409);
		assertJson(response, "{\"email\": \"Email taken\"}");
		assertThat(memberCount()).isEqualTo(1);
	}

	@Test
	void invalidMemberIsAnsweredWithTheLegacyMessage() throws Exception {
		HttpResponse<String> response = post("""
				{"name": "Bad 123", "email": "bad@example.com", "phoneNumber": "2125551234"}""");

		assertThat(response.statusCode()).isEqualTo(400);
		assertJson(response, "{\"name\": \"must contain only letters and spaces\"}");
		assertThat(memberCount()).isEqualTo(1);
	}

	@Test
	void memberSentWithAnIdIsRejectedLikeLegacy() throws Exception {
		HttpResponse<String> response = post("""
				{"id": 999, "name": "Jane Doe", "email": "jane.doe@example.com", "phoneNumber": "2125551234"}""");

		assertThat(response.statusCode()).isEqualTo(400);
		assertJson(response, """
				{"error": "jakarta.persistence.EntityExistsException: detached entity passed to persist: org.jboss.as.quickstarts.kitchensink.model.Member"}""");
		assertThat(memberCount()).isEqualTo(1);
	}

	@Test
	void malformedJsonIsAnsweredLikeLegacy() throws Exception {
		HttpResponse<String> response = post("{\"name\":");

		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				contentType -> assertThat(contentType).startsWith("application/json"));
		assertThat(response.body()).startsWith("RESTEASY008200: JSON Binding deserialization error: ");
	}

	private void assertJson(HttpResponse<String> response, String expected) {
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				contentType -> assertThat(contentType).startsWith("application/json"));
		assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree(expected));
	}

	private long memberCount() {
		return jdbcClient.sql("SELECT COUNT(*) FROM Member").query(Long.class).single();
	}

	private HttpResponse<String> post(String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/rest/members"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
