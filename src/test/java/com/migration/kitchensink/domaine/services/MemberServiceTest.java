package com.migration.kitchensink.domaine.services;

import java.util.Optional;

import com.migration.kitchensink.domaine.exceptions.MemberNotFoundException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.infrastructure.database.MemberPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

	@Mock
	private MemberPersistence memberPersistence;

	@InjectMocks
	private MemberService memberService;

	@Test
	void findByIdReturnsTheMember() {
		Member member = new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234");
		given(memberPersistence.findById(1L)).willReturn(Optional.of(member));

		assertThat(memberService.findById(1L)).isEqualTo(member);
	}

	@Test
	void findByIdThrowsMemberNotFoundExceptionForUnknownId() {
		given(memberPersistence.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> memberService.findById(99L)).isInstanceOf(MemberNotFoundException.class)
			.hasMessageContaining("99");
	}

	@Test
	void synchronizeStoresTheMember() {
		Member member = new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234");

		memberService.synchronize(member);

		then(memberPersistence).should().save(member);
	}

	@Test
	void removeDeletesTheMember() {
		memberService.remove(1L);

		then(memberPersistence).should().deleteById(1L);
	}

}
