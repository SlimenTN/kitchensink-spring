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
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "migration.cdc.enabled=false")
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

	// Expected order taken from legacy MySQL (utf8mb4_0900_ai_ci): case- and accent-insensitive.
	// A plain MongoDB sort would give "Eve, Jane Doe, alice, bob, charlie, Émile".
	@Test
	void listsAllMembersSortedByNameLikeLegacy() throws Exception {
		insertMember(21L, "bob", "bob@example.com", "2125550021");
		insertMember(22L, "alice", "alice@example.com", "2125550022");
		insertMember(23L, "Émile", "emile@example.com", "2125550023");
		insertMember(24L, "charlie", "charlie@example.com", "2125550024");
		insertMember(25L, "Eve", "eve@example.com", "2125550025");

		HttpResponse<String> response = get("/rest/members");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				contentType -> assertThat(contentType).startsWith("application/json"));
		// JSON array equality: same members, same values, same order
		assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree("""
				[
				  {"email": "alice@example.com", "id": 22, "name": "alice", "phoneNumber": "2125550022"},
				  {"email": "bob@example.com", "id": 21, "name": "bob", "phoneNumber": "2125550021"},
				  {"email": "charlie@example.com", "id": 24, "name": "charlie", "phoneNumber": "2125550024"},
				  {"email": "emile@example.com", "id": 23, "name": "Émile", "phoneNumber": "2125550023"},
				  {"email": "eve@example.com", "id": 25, "name": "Eve", "phoneNumber": "2125550025"},
				  {"email": "jane.doe@example.com", "id": 7, "name": "Jane Doe", "phoneNumber": "2125551234"}
				]
				"""));
	}

	@Test
	void listsAnEmptyArrayWhenThereAreNoMembers() throws Exception {
		mongoTemplate.getCollection("members").deleteMany(new Document());

		HttpResponse<String> response = get("/rest/members");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				contentType -> assertThat(contentType).startsWith("application/json"));
		assertThat(response.body()).isEqualTo("[]");
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

	private void insertMember(long id, String name, String email, String phoneNumber) {
		mongoTemplate.getCollection("members")
			.insertOne(new Document("_id", id).append("name", name).append("email", email).append("phoneNumber", phoneNumber));
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("Accept", "application/json")
			.GET()
			.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
