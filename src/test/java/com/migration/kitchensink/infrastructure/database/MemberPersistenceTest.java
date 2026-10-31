package com.migration.kitchensink.infrastructure.database;

import java.util.Optional;

import com.migration.kitchensink.domaine.models.Member;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class MemberPersistenceTest {

	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private MemberPersistence memberPersistence;

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
	void saveMapsTheDomainMemberToADocument() {
		memberPersistence.save(new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234"));

		then(memberRepository).should()
			.save(new MemberDocument(1L, "Jane Doe", "jane.doe@example.com", "2125551234"));
	}

	@Test
	void deleteByIdDeletesTheDocument() {
		memberPersistence.deleteById(1L);

		then(memberRepository).should().deleteById(1L);
	}

}
