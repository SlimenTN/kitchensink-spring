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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

// GET /rest/members/{id} through every layer: HTTP -> rest -> domaine -> database -> MongoDB
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MemberApiIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private MongoTemplate mongoTemplate;

	@BeforeEach
	void seedMembers() {
		// raw document, shaped like the real data: _id is a NumberLong (Java Long), no _class field
		mongoTemplate.getCollection("members").deleteMany(new Document());
		mongoTemplate.getCollection("members")
			.insertOne(new Document("_id", 7L).append("name", "Jane Doe")
				.append("email", "jane.doe@example.com")
				.append("phoneNumber", "2125551234"));
	}

	@Test
	void returnsTheMemberStoredInMongoDb() throws Exception {
		HttpResponse<String> response = get("/rest/members/7");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				contentType -> assertThat(contentType).startsWith("application/json"));
		assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree("""
				{"email": "jane.doe@example.com", "id": 7, "name": "Jane Doe", "phoneNumber": "2125551234"}
				"""));
	}

	@Test
	void unknownMemberReturnsBare404() throws Exception {
		HttpResponse<String> response = get("/rest/members/999");

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).isEmpty();
		assertThat(response.headers().firstValue("Content-Type")).isEmpty();
	}

	// Only a real server renders Spring's default JSON error body, which MockMvc can't show
	@Test
	void nonNumericIdReturnsBare404() throws Exception {
		HttpResponse<String> response = get("/rest/members/abc");

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).isEmpty();
		assertThat(response.headers().firstValue("Content-Type")).isEmpty();
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("Accept", "application/json")
			.GET()
			.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
