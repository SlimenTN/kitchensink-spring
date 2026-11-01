package com.migration.kitchensink.infrastructure.database;

import java.util.List;
import java.util.Optional;

import com.migration.kitchensink.domaine.exceptions.EmailAlreadyTakenException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import com.migration.kitchensink.infrastructure.legacydatabase.LegacyMemberPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class MemberPersistenceTest {

	private static final NewMember JANE = new NewMember(null, "Jane Doe", "jane.doe@example.com", "2125551234");

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private LegacyMemberPersistence legacyRepository;

	@Mock
	private MemberIdSequence idSequence;

	// write store "legacy", as configured during the migration
	private MemberPersistence memberPersistence;

	@BeforeEach
	void setUp() {
		memberPersistence = new MemberPersistence(memberRepository, legacyRepository, idSequence, "legacy");
	}

	private MemberPersistence onNewWriteStore() {
		return new MemberPersistence(memberRepository, legacyRepository, idSequence, "new");
	}

	@Test
	void legacyWriteStoreCreatesTheMemberInTheLegacyDatabase() {
		Member stored = new Member(201L, "Jane Doe", "jane.doe@example.com", "2125551234");
		given(legacyRepository.insert(JANE)).willReturn(stored);

		assertThat(memberPersistence.create(JANE)).isEqualTo(stored);
		then(memberRepository).shouldHaveNoInteractions();
	}

	@Test
	void legacyWriteStoreChecksTheEmailInTheLegacyDatabase() {
		given(legacyRepository.existsByEmail("john.smith@mailinator.com")).willReturn(true);

		assertThat(memberPersistence.existsByEmail("john.smith@mailinator.com")).isTrue();
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
		assertThatThrownBy(() -> new MemberPersistence(memberRepository, legacyRepository, idSequence, "legasy"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'legacy' or 'new'")
			.hasMessageContaining("legasy");
	}

	// reads and CDC never depend on the write store
	@ParameterizedTest
	@ValueSource(strings = { "legacy", "new" })
	void readsAndSynchronizationAlwaysUseMongoDb(String writeStore) {
		MemberPersistence persistence = new MemberPersistence(memberRepository, legacyRepository, idSequence,
				writeStore);
		given(memberRepository.findById(1L)).willReturn(Optional.empty());

		persistence.findById(1L);
		persistence.synchronize(new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234"));
		persistence.deleteById(1L);

		then(memberRepository).should().findById(1L);
		then(memberRepository).should().save(new MemberDocument(1L, "Jane Doe", "jane.doe@example.com", "2125551234"));
		then(memberRepository).should().deleteById(1L);
		then(legacyRepository).shouldHaveNoInteractions();
	}

	@Test
	void findByIdMapsTheDocumentToADomainMember() {
		given(memberRepository.findById(1L))
			.willReturn(Optional.of(new MemberDocument(1L, "Jane Doe", "jane.doe@example.com", "2125551234")));

		assertThat(memberPersistence.findById(1L))
			.contains(new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234"));
	}

	@Test
	void findByIdReturnsEmptyWhenNoDocument() {
		given(memberRepository.findById(99L)).willReturn(Optional.empty());

		assertThat(memberPersistence.findById(99L)).isEmpty();
	}

	@Test
	void findAllOrderedByNameMapsEveryDocumentAndKeepsTheRepositoryOrder() {
		given(memberRepository.findAllByOrderByNameAsc())
			.willReturn(List.of(new MemberDocument(2L, "alice", "alice@example.com", "2125550001"),
					new MemberDocument(1L, "Bob", "bob@example.com", "2125550002")));

		assertThat(memberPersistence.findAllOrderedByName()).containsExactly(
				new Member(2L, "alice", "alice@example.com", "2125550001"),
				new Member(1L, "Bob", "bob@example.com", "2125550002"));
	}

	@Test
	void findAllOrderedByNameReturnsAnEmptyListWhenNoDocument() {
		given(memberRepository.findAllByOrderByNameAsc()).willReturn(List.of());

		assertThat(memberPersistence.findAllOrderedByName()).isEmpty();
	}

	@Test
	void synchronizeMapsTheDomainMemberToADocument() {
		memberPersistence.synchronize(new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234"));

		then(memberRepository).should()
			.save(new MemberDocument(1L, "Jane Doe", "jane.doe@example.com", "2125551234"));
	}

	@Test
	void deleteByIdDeletesTheDocument() {
		memberPersistence.deleteById(1L);

		then(memberRepository).should().deleteById(1L);
	}

}
