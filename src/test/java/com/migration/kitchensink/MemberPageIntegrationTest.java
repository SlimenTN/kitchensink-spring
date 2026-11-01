package com.migration.kitchensink;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

// The members page through every layer, with CDC on: form -> domain -> legacy MySQL -> CDC -> MongoDB -> page
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
@Import(TestcontainersConfiguration.class)
class MemberPageIntegrationTest {

	// the legacy database: written by the registration (datasource) and read by CDC (binlog, root)
	@Container
	@ServiceConnection
	static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0").withDatabaseName("kitchensink")
		.withInitScript("cdc/legacy-member-schema.sql");

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private MongoTemplate mongoTemplate;

	private static boolean cdcStreaming;

	@DynamicPropertySource
	static void cdcProperties(DynamicPropertyRegistry registry) throws Exception {
		Path stateDirectory = Files.createTempDirectory("kitchensink-page-cdc-test");
		registry.add("migration.cdc.enabled", () -> "true");
		registry.add("migration.cdc.mysql.host", MYSQL::getHost);
		registry.add("migration.cdc.mysql.port", () -> MYSQL.getMappedPort(3306));
		registry.add("migration.cdc.mysql.user", () -> "root");
		registry.add("migration.cdc.mysql.password", MYSQL::getPassword);
		registry.add("migration.cdc.mysql.database", () -> "kitchensink");
		registry.add("migration.cdc.mysql.table", () -> "Member");
		registry.add("migration.cdc.mysql.server-id", () -> "5498");
		registry.add("migration.cdc.state-directory", stateDirectory::toString);
	}

	/**
	 * Right after startup CDC takes a few seconds (initial snapshot) before it streams changes; a running system has
	 * been streaming for long. Wait for that steady state: a canary row written to MySQL must reach MongoDB.
	 */
	@BeforeEach
	void awaitCdcStreaming() throws Exception {
		if (cdcStreaming) {
			return;
		}
		jdbcClient.sql("INSERT INTO Member (id, email, name, phone_number) VALUES (999999, 'cdc.canary@example.com', "
				+ "'Cdc Canary', '2125550000')").update();
		Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
		while (mongoTemplate.getCollection("members").find(eq("_id", 999999L)).first() == null) {
			assertThat(Instant.now()).as("CDC streaming within 60 s").isBefore(deadline);
			Thread.sleep(200);
		}
		jdbcClient.sql("DELETE FROM Member WHERE id = 999999").update();
		cdcStreaming = true;
	}

	// like legacy, the page returned by a successful registration already lists the new member
	@Test
	void registeredMemberIsListedOnTheReturnedPage() throws Exception {
		HttpResponse<String> response = submit("/index.jsf",
				form("Page Member", "page.member@example.com", "2125550701"));

		assertThat(response.statusCode()).isEqualTo(200);
		Document page = Jsoup.parse(response.body());
		assertThat(page.select("ul.messages li.valid").text()).isEqualTo("Registered!");
		assertThat(page.select("table.simpletablestyle tbody").text()).contains("page.member@example.com");
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM Member WHERE email = ?")
			.param("page.member@example.com")
			.query(Long.class)
			.single()).as("stored in the legacy database").isEqualTo(1L);
	}

	// a form rendered by the legacy JSF page: ;jsessionid in the URL, ViewState and other hidden fields
	@Test
	void formRenderedByTheLegacyPageIsAcceptedByTheServer() throws Exception {
		Map<String, String> legacyForm = new LinkedHashMap<>();
		legacyForm.put("reg", "reg");
		legacyForm.putAll(form("Legacy Form", "legacy.form@example.com", "2125550702"));
		legacyForm.put("jakarta.faces.ViewState", "-1234567890:987654321");

		HttpResponse<String> response = submit("/index.jsf;jsessionid=1fGHNeNxw4rWEbc", legacyForm);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(Jsoup.parse(response.body()).select("ul.messages li.valid").text()).isEqualTo("Registered!");
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM Member WHERE email = ?")
			.param("legacy.form@example.com")
			.query(Long.class)
			.single()).isEqualTo(1L);
	}

	private static Map<String, String> form(String name, String email, String phoneNumber) {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("reg:name", name);
		form.put("reg:email", email);
		form.put("reg:phoneNumber", phoneNumber);
		form.put("reg:register", "Register");
		return form;
	}

	private HttpResponse<String> submit(String path, Map<String, String> form) throws Exception {
		String body = form.entrySet()
			.stream()
			.map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
					+ URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept-Language", "en")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
