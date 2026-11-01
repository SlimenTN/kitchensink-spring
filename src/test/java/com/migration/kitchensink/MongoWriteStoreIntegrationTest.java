package com.migration.kitchensink;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * After cutover: MongoDB is the write store (migration.write-store=new). Registrations (REST and page) go to MongoDB
 * with ids continuing from the biggest existing id; the legacy MySQL database is not used at all (unreachable datasource here).
 */
@SpringBootTest(webEnvironment = RANDOM_PORT,
		properties = { "migration.write-store=new", "migration.cdc.enabled=false",
				"spring.datasource.url=jdbc:mysql://localhost:1/unreachable" })
@Import(TestcontainersConfiguration.class)
class MongoWriteStoreIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private MongoTemplate mongoTemplate;

	// like the real database (mongodb/init/01-members.js): unique email, case- and accent-insensitive; one member
	// copied from legacy by CDC, with a Member_SEQ id
	@BeforeEach
	void resetMongoDb() {
		mongoTemplate.getCollection("members").drop();
		mongoTemplate.getCollection("counters").drop();
		mongoTemplate.getCollection("members")
			.createIndex(Indexes.ascending("email"), new IndexOptions().unique(true)
				.name("uk_members_email")
				.collation(Collation.builder().locale("en").collationStrength(CollationStrength.PRIMARY).build()));
		mongoTemplate.getCollection("members")
			.insertOne(new Document("_id", 561L).append("name", "John Smith")
				.append("email", "john.smith@mailinator.com")
				.append("phoneNumber", "2125551212"));
	}

	@Test
	void restRegistrationsAreStoredInMongoDbWithIdsAfterTheBiggestExistingId() throws Exception {
		HttpResponse<String> first = postJson(member("Jane Doe", "jane.doe@example.com", "2125551234"));
		HttpResponse<String> second = postJson(member("Max Muster", "max@example.com", "2125551235"));

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(first.body()).isEmpty();
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(stored(562L)).isEqualTo(new Document("_id", 562L).append("name", "Jane Doe")
			.append("email", "jane.doe@example.com")
			.append("phoneNumber", "2125551234"));
		assertThat(stored(563L).getString("email")).isEqualTo("max@example.com");
		assertThat(JSON.readTree(get("/rest/members/562").body()).get("name").asString()).isEqualTo("Jane Doe");
	}

	// like the legacy MySQL unique key: case-insensitive
	@Test
	void anEmailAlreadyTakenInAnotherCaseIsRejected() throws Exception {
		HttpResponse<String> response = postJson(member("Someone Else", "JOHN.SMITH@MAILINATOR.COM", "2125551236"));

		assertThat(response.statusCode()).isEqualTo(409);
		assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree("{\"email\": \"Email taken\"}"));
		assertThat(mongoTemplate.getCollection("members").countDocuments()).isEqualTo(1);
	}

	// no CDC involved: the page lists the new member right away
	@Test
	void pageRegistrationIsStoredInMongoDbAndListedRightAway() throws Exception {
		String form = "reg%3Aname=Page+Member&reg%3Aemail=" + URLEncoder.encode("page@example.com", StandardCharsets.UTF_8)
				+ "&reg%3AphoneNumber=2125551237&reg%3Aregister=Register";
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/index.jsf"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept-Language", "en")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build(), HttpResponse.BodyHandlers.ofString());

		var page = Jsoup.parse(response.body());
		assertThat(page.select("ul.messages li.valid").text()).isEqualTo("Registered!");
		assertThat(page.select("table.simpletablestyle tbody").text()).contains("page@example.com");
		assertThat(mongoTemplate.getCollection("members").countDocuments(eq("email", "page@example.com"))).isEqualTo(1);
	}

	@Test
	void concurrentRegistrationsGetDistinctIds() throws Exception {
		List<Callable<HttpResponse<String>>> registrations = IntStream.range(0, 20)
			.<Callable<HttpResponse<String>>>mapToObj(
					i -> () -> postJson(member("Member", "member" + i + "@example.com", "2125551238")))
			.toList();

		List<Integer> statuses = runConcurrently(registrations);

		assertThat(statuses).containsOnly(200);
		List<Long> ids = mongoTemplate.getCollection("members")
			.distinct("_id", Long.class)
			.into(new java.util.ArrayList<>())
			.stream()
			.filter(id -> id > 561L)
			.toList();
		assertThat(ids).hasSize(20).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).isBetween(562L, 581L));
	}

	// the email check and the insert are two steps: the unique index decides between simultaneous registrations
	@Test
	void simultaneousRegistrationsWithTheSameEmailStoreOnlyOne() throws Exception {
		List<Callable<HttpResponse<String>>> registrations = IntStream.range(0, 10)
			.<Callable<HttpResponse<String>>>mapToObj(i -> () -> postJson(member("Same", "same@example.com", "2125551239")))
			.toList();

		List<Integer> statuses = runConcurrently(registrations);

		assertThat(statuses).containsOnly(200, 409).filteredOn(status -> status == 200).hasSize(1);
		assertThat(mongoTemplate.getCollection("members").countDocuments(eq("email", "same@example.com"))).isEqualTo(1);
	}

	private Document stored(long id) {
		return mongoTemplate.getCollection("members").find(eq("_id", id)).first();
	}

	private static String member(String name, String email, String phoneNumber) {
		return "{\"name\": \"%s\", \"email\": \"%s\", \"phoneNumber\": \"%s\"}".formatted(name, email, phoneNumber);
	}

	private HttpResponse<String> postJson(String json) throws Exception {
		return http.send(HttpRequest.newBuilder(uri("/rest/members"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private static List<Integer> runConcurrently(List<Callable<HttpResponse<String>>> calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(10);
		try {
			List<Integer> statuses = new java.util.ArrayList<>();
			for (Future<HttpResponse<String>> call : pool.invokeAll(calls)) {
				statuses.add(call.get().statusCode());
			}
			return statuses;
		}
		finally {
			pool.shutdown();
		}
	}

}
