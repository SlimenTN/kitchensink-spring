package com.migration.kitchensink.infrastructure.database;

import java.util.List;
import java.util.Optional;

import com.migration.kitchensink.domaine.exceptions.EmailAlreadyTakenException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import com.migration.kitchensink.infrastructure.legacydatabase.LegacyDatabasePersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.BulkOperations.BulkMode;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@ExtendWith(MockitoExtension.class)
class DatabasePersistenceTest {

	private static final NewMember JANE = new NewMember(null, "Jane Doe", "jane.doe@example.com", "2125551234");

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private LegacyDatabasePersistence legacyRepository;

	@Mock
	private MemberIdSequence idSequence;

	@Mock
	private MongoTemplate mongoTemplate;

	@Mock
	private BulkOperations bulk;

	// write store "legacy", as configured during the migration
	private DatabasePersistence databasePersistence;

	@BeforeEach
	void setUp() {
		databasePersistence = new DatabasePersistence(memberRepository, legacyRepository, idSequence, mongoTemplate, "legacy");
	}

	private DatabasePersistence onNewWriteStore() {
		return new DatabasePersistence(memberRepository, legacyRepository, idSequence, mongoTemplate, "new");
	}

	@Test
	void legacyWriteStoreCreatesTheMemberInTheLegacyDatabase() {
		Member stored = new Member(201L, "Jane Doe", "jane.doe@example.com", "2125551234");
		given(legacyRepository.insert(JANE)).willReturn(stored);

		assertThat(databasePersistence.create(JANE)).isEqualTo(stored);
		then(memberRepository).shouldHaveNoInteractions();
	}

	@Test
	void legacyWriteStoreChecksTheEmailInTheLegacyDatabase() {
		given(legacyRepository.existsByEmail("john.smith@mailinator.com")).willReturn(true);

		assertThat(databasePersistence.existsByEmail("john.smith@mailinator.com")).isTrue();
		then(memberRepository).shouldHaveNoInteractions();
	}

	@Test
	void newWriteStoreCreatesTheMemberInMongoDbWithAnIdFromTheSequence() {
		given(idSequence.next()).willReturn(1_000_007L);

		assertThat(onNewWriteStore().create(JANE))
			.isEqualTo(new Member(1_000_007L, "Jane Doe", "jane.doe@example.com", "2125551234"));
		// insert, never save: an existing member must not be replaced
		then(memberRepository).should()
			.insert(new MemberDocument(1_000_007L, "Jane Doe", "jane.doe@example.com", "2125551234"));
		then(legacyRepository).shouldHaveNoInteractions();
	}

	@Test
	void legacyWriteStoreReadsTheMembersAfterARegistrationFromTheLegacyDatabase() {
		Member jane = new Member(201L, "Jane Doe", "jane.doe@example.com", "2125551234");
		given(legacyRepository.findAllOrderedByName()).willReturn(List.of(jane));

		assertThat(databasePersistence.findAllOrderedByNameAfterRegistration()).containsExactly(jane);
		then(memberRepository).shouldHaveNoInteractions();
	}

	@Test
	void newWriteStoreReadsTheMembersAfterARegistrationFromMongoDb() {
		given(memberRepository.findAllByOrderByNameAsc())
			.willReturn(List.of(new MemberDocument(1_000_007L, "Jane Doe", "jane.doe@example.com", "2125551234")));

		assertThat(onNewWriteStore().findAllOrderedByNameAfterRegistration())
			.containsExactly(new Member(1_000_007L, "Jane Doe", "jane.doe@example.com", "2125551234"));
		then(legacyRepository).shouldHaveNoInteractions();
	}

	@Test
	void newWriteStoreChecksTheEmailInMongoDb() {
		given(memberRepository.existsByEmail("john.smith@mailinator.com")).willReturn(true);

		assertThat(onNewWriteStore().existsByEmail("john.smith@mailinator.com")).isTrue();
		then(legacyRepository).shouldHaveNoInteractions();
	}

	// two registrations with the same email at the same moment: the unique email index rejects the second
	@Test
	void newWriteStoreTurnsADuplicateEmailIntoEmailAlreadyTaken() {
		given(idSequence.next()).willReturn(1_000_008L);
		given(memberRepository.insert(any(MemberDocument.class)))
			.willThrow(new DuplicateKeyException("E11000 duplicate key error collection: kitchensink.members index: uk_members_email"));

		assertThatThrownBy(() -> onNewWriteStore().create(JANE)).isInstanceOf(EmailAlreadyTakenException.class);
	}

	@Test
	void unknownWriteStoreIsRejectedAtStartup() {
		assertThatThrownBy(() -> new DatabasePersistence(memberRepository, legacyRepository, idSequence, mongoTemplate, "legasy"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'legacy' or 'new'")
			.hasMessageContaining("legasy");
	}

	// reads and CDC never depend on the write store
	@ParameterizedTest
	@ValueSource(strings = { "legacy", "new" })
	void readsAndSynchronizationAlwaysUseMongoDb(String writeStore) {
		DatabasePersistence persistence = new DatabasePersistence(memberRepository, legacyRepository, idSequence,
				mongoTemplate, writeStore);
		given(memberRepository.findById(1L)).willReturn(Optional.empty());
		given(mongoTemplate.bulkOps(BulkMode.ORDERED, MemberDocument.class)).willReturn(bulk);

		List<MemberChange> changes = List.of(
				new MemberChange.Upsert(new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234")),
				new MemberChange.Removal(2L));

		persistence.findById(1L);
		persistence.synchronize(changes);

		then(memberRepository).should().findById(1L);
		then(bulk).should().execute();
		then(legacyRepository).shouldHaveNoInteractions();
	}

	// one ordered bulk write, the changes in their order
	@Test
	void synchronizeAppliesTheChangesInOrderInOneBulkWrite() {
		given(mongoTemplate.bulkOps(BulkMode.ORDERED, MemberDocument.class)).willReturn(bulk);

		databasePersistence.synchronize(List.of(
				new MemberChange.Upsert(new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234")),
				new MemberChange.Removal(2L)));

		InOrder inOrder = inOrder(bulk);
		then(bulk).should(inOrder).upsert(Query.query(where("_id").is(1L)), Update.update("name", "Jane Doe")
			.set("email", "jane.doe@example.com")
			.set("phoneNumber", "2125551234"));
		then(bulk).should(inOrder).remove(Query.query(where("_id").is(2L)));
		then(bulk).should(inOrder).execute();
	}

	@Test
	void synchronizeAnEmptyBatchWritesNothing() {
		databasePersistence.synchronize(List.of());

		then(mongoTemplate).shouldHaveNoInteractions();
	}

	@Test
	void findByIdMapsTheDocumentToADomainMember() {
		given(memberRepository.findById(1L))
			.willReturn(Optional.of(new MemberDocument(1L, "Jane Doe", "jane.doe@example.com", "2125551234")));

		assertThat(databasePersistence.findById(1L))
			.contains(new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234"));
	}

	@Test
	void findByIdReturnsEmptyWhenNoDocument() {
		given(memberRepository.findById(99L)).willReturn(Optional.empty());

		assertThat(databasePersistence.findById(99L)).isEmpty();
	}

	@Test
	void findAllOrderedByNameMapsEveryDocumentAndKeepsTheRepositoryOrder() {
		given(memberRepository.findAllByOrderByNameAsc())
			.willReturn(List.of(new MemberDocument(2L, "alice", "alice@example.com", "2125550001"),
					new MemberDocument(1L, "Bob", "bob@example.com", "2125550002")));

		assertThat(databasePersistence.findAllOrderedByName()).containsExactly(
				new Member(2L, "alice", "alice@example.com", "2125550001"),
				new Member(1L, "Bob", "bob@example.com", "2125550002"));
	}

	@Test
	void findAllOrderedByNameReturnsAnEmptyListWhenNoDocument() {
		given(memberRepository.findAllByOrderByNameAsc()).willReturn(List.of());

		assertThat(databasePersistence.findAllOrderedByName()).isEmpty();
	}

}
