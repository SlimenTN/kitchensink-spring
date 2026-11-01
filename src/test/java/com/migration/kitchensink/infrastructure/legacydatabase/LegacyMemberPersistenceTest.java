package com.migration.kitchensink.infrastructure.legacydatabase;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import com.migration.kitchensink.LegacyMySqlTestcontainersConfiguration;
import com.migration.kitchensink.TestcontainersConfiguration;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

// Real MySQL with the legacy schema: the Member_SEQ protocol only means something with real transactions and locks
@SpringBootTest(properties = "migration.cdc.enabled=false")
@Import({ LegacyMySqlTestcontainersConfiguration.class, TestcontainersConfiguration.class })
class LegacyMemberPersistenceTest {

	@Autowired
	private LegacyMemberPersistence legacyMemberPersistence;

	@Autowired
	private JdbcClient jdbcClient;

	@BeforeEach
	void resetLegacyTables() {
		jdbcClient.sql("DELETE FROM Member WHERE id <> 0").update();
		jdbcClient.sql("UPDATE Member_SEQ SET next_val = 151").update();
	}

	@Test
	void insertStoresTheMemberWithTheNextIdOfMemberSeq() {
		Member stored = legacyMemberPersistence.insert(new NewMember(null, "Jane Doe", "jane.doe@example.com", "2125551234"));

		assertThat(stored).isEqualTo(new Member(151L, "Jane Doe", "jane.doe@example.com", "2125551234"));
		assertThat(row(151L)).isEqualTo(new Member(151L, "Jane Doe", "jane.doe@example.com", "2125551234"));
		assertThat(nextVal()).isEqualTo(152L);
	}

	// The legacy app reserves [next_val, next_val + 49] and moves next_val by 50; it then hands out those ids from
	// memory. An id taken from Member_SEQ can therefore never fall inside a block the legacy app holds.
	@Test
	void neverHandsOutAnIdFromABlockReservedByTheLegacyApp() {
		long legacyBlockStart = nextVal();
		jdbcClient.sql("UPDATE Member_SEQ SET next_val = next_val + 50").update(); // legacy reserves 151..200

		Member stored = legacyMemberPersistence.insert(new NewMember(null, "Jane Doe", "jane.doe@example.com", "2125551234"));

		assertThat(stored.id()).isEqualTo(201L).isGreaterThan(legacyBlockStart + 49);
	}

	@Test
	void concurrentInsertsGetDistinctIds() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(8);
		List<Callable<Member>> inserts = IntStream.range(0, 20)
			.<Callable<Member>>mapToObj(i -> () -> legacyMemberPersistence
				.insert(new NewMember(null, "Member", "member" + i + "@example.com", "2125551234")))
			.toList();

		List<Long> ids = pool.invokeAll(inserts).stream().map(LegacyMemberPersistenceTest::idOf).toList();
		pool.shutdown();

		assertThat(ids).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).isBetween(151L, 170L));
		assertThat(nextVal()).isEqualTo(171L);
	}

	@Test
	void existsByEmailFindsAnExistingMember() {
		assertThat(legacyMemberPersistence.existsByEmail("john.smith@mailinator.com")).isTrue();
		assertThat(legacyMemberPersistence.existsByEmail("nobody@example.com")).isFalse();
	}

	private long nextVal() {
		return jdbcClient.sql("SELECT next_val FROM Member_SEQ").query(Long.class).single();
	}

	private Member row(long id) {
		return jdbcClient.sql("SELECT id, name, email, phone_number FROM Member WHERE id = ?")
			.param(id)
			.query((rs, n) -> new Member(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
					rs.getString("phone_number")))
			.single();
	}

	private static long idOf(Future<Member> insert) {
		try {
			return insert.get().id();
		}
		catch (Exception e) {
			throw new IllegalStateException("concurrent insert failed", e);
		}
	}

}
