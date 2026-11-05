package com.migration.kitchensink.infrastructure.database;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import com.migration.kitchensink.TestcontainersConfiguration;
import com.migration.kitchensink.domaine.models.Member;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

// The CDC bulk write against a real MongoDB
@SpringBootTest(properties = "migration.cdc.enabled=false")
@Import(TestcontainersConfiguration.class)
class DatabasePersistenceSynchronizationTest {

	@Autowired
	private DatabasePersistence databasePersistence;

	@Autowired
	private MongoTemplate mongoTemplate;

	@BeforeEach
	void emptyCollection() {
		mongoTemplate.getCollection("members").deleteMany(new Document());
	}

	@Test
	void upsertsCreateAndReplaceMembersWithExactlyTheMemberFields() {
		databasePersistence.synchronize(List.of(upsert(1L, "Jane Doe", "jane.doe@example.com", "2125551234")));
		databasePersistence.synchronize(List.of(upsert(1L, "Jane Smith", "jane@example.org", "2125550000")));

		// no _class or other field: the collection validator accepts only the member fields
		assertThat(stored(1L)).isEqualTo(new Document("_id", 1L).append("name", "Jane Smith")
			.append("email", "jane@example.org")
			.append("phoneNumber", "2125550000"));
	}

	@Test
	void changesAreAppliedInTheirOrder() {
		databasePersistence.synchronize(List.of(upsert(1L, "Jane Doe", "jane.doe@example.com", "2125551234"),
				new MemberChange.Removal(1L), upsert(2L, "John Smith", "john@example.com", "2125551212"),
				upsert(2L, "John Smyth", "john@example.com", "2125551212")));

		assertThat(stored(1L)).isNull();
		assertThat(stored(2L).getString("name")).isEqualTo("John Smyth");
	}

	@Test
	void removingAnUnknownMemberIsIgnored() {
		databasePersistence.synchronize(List.of(new MemberChange.Removal(99L)));

		assertThat(mongoTemplate.getCollection("members").countDocuments()).isZero();
	}

	@Test
	void anEmptyBatchWritesNothing() {
		databasePersistence.synchronize(List.of());

		assertThat(mongoTemplate.getCollection("members").countDocuments()).isZero();
	}

	// a snapshot batch of a large table: thousands of members in one call
	@Test
	void aLargeBatchIsWrittenCompletely() {
		List<MemberChange> snapshot = new ArrayList<>();
		LongStream.rangeClosed(1, 5_000)
			.forEach(id -> snapshot.add(upsert(id, "Member", "member" + id + "@example.com", "2125551234")));

		databasePersistence.synchronize(snapshot);

		assertThat(mongoTemplate.getCollection("members").countDocuments()).isEqualTo(5_000);
	}

	private Document stored(long id) {
		return mongoTemplate.getCollection("members").find(eq("_id", id)).first();
	}

	private static MemberChange upsert(long id, String name, String email, String phoneNumber) {
		return new MemberChange.Upsert(new Member(id, name, email, phoneNumber));
	}

}
