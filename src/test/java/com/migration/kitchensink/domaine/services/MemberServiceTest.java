package com.migration.kitchensink.domaine.services;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import com.migration.kitchensink.domaine.exceptions.EmailAlreadyTakenException;
import com.migration.kitchensink.domaine.exceptions.MemberIdNotAllowedException;
import com.migration.kitchensink.domaine.exceptions.MemberNotFoundException;
import com.migration.kitchensink.domaine.exceptions.MemberValidationException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import com.migration.kitchensink.infrastructure.database.MemberPersistence;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

	// real Bean Validation: the field rules and their messages are part of what the domain must get right
	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Mock
	private MemberPersistence memberPersistence;

	private MemberService memberService;

	@BeforeEach
	void setUp() {
		memberService = new MemberService(memberPersistence, VALIDATOR);
	}

	@Test
	void registerCreatesAValidMemberThroughThePersistenceAndReturnsIt() {
		NewMember jane = new NewMember(null, "Jane Doe", "jane.doe@example.com", "2125551234");
		Member stored = new Member(201L, "Jane Doe", "jane.doe@example.com", "2125551234");
		given(memberPersistence.existsByEmail("jane.doe@example.com")).willReturn(false);
		given(memberPersistence.create(jane)).willReturn(stored);

		assertThat(memberService.register(jane)).isEqualTo(stored);
		// registration goes through create(); MongoDB is only updated by CDC (synchronize)
		then(memberPersistence).should(never()).synchronize(any());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidMembers")
	void registerRejectsAnInvalidMemberWithTheLegacyMessage(String scenario, NewMember member, String field,
			String message) {
		assertThatThrownBy(() -> memberService.register(member)).isInstanceOfSatisfying(MemberValidationException.class,
				exception -> assertThat(exception.violations()).containsExactly(entry(field, message)));
		then(memberPersistence).shouldHaveNoInteractions();
	}

	static Stream<Arguments> invalidMembers() {
		return Stream.of(
				arguments("name with digits", new NewMember(null, "Bad 123", "a@example.com", "2125551234"), "name",
						"must contain only letters and spaces"),
				arguments("name too long", new NewMember(null, "Abcdefghijklmnopqrstuvwxyz", "a@example.com", "2125551234"),
						"name", "size must be between 1 and 25"),
				arguments("name missing", new NewMember(null, null, "a@example.com", "2125551234"), "name",
						"must not be null"),
				arguments("invalid email", new NewMember(null, "Jane Doe", "not-an-email", "2125551234"), "email",
						"must be a well-formed email address"),
				arguments("phone too short", new NewMember(null, "Jane Doe", "a@example.com", "12345"), "phoneNumber",
						"size must be between 10 and 12"));
	}

	@Test
	void registerReportsOneMessagePerInvalidField() {
		NewMember member = new NewMember(null, "Bad 123", "not-an-email", "12345");

		assertThatThrownBy(() -> memberService.register(member)).isInstanceOfSatisfying(MemberValidationException.class,
				exception -> assertThat(exception.violations()).containsOnlyKeys("name", "email", "phoneNumber"));
	}

	@Test
	void registerRejectsAnEmailAlreadyTaken() {
		given(memberPersistence.existsByEmail("john.smith@mailinator.com")).willReturn(true);

		assertThatThrownBy(() -> memberService
			.register(new NewMember(null, "Someone Else", "john.smith@mailinator.com", "2125551234")))
			.isInstanceOf(EmailAlreadyTakenException.class);
		then(memberPersistence).should(never()).create(any());
	}

	@Test
	void registerRejectsAMemberThatAlreadyHasAnId() {
		given(memberPersistence.existsByEmail("jane.doe@example.com")).willReturn(false);

		assertThatThrownBy(() -> memberService.register(new NewMember(999L, "Jane Doe", "jane.doe@example.com", "2125551234")))
			.isInstanceOf(MemberIdNotAllowedException.class);
		then(memberPersistence).should(never()).create(any());
	}

	// legacy order: field rules, then email uniqueness, then the id
	@Test
	void fieldRulesAreCheckedBeforeEmailUniquenessAndId() {
		assertThatThrownBy(() -> memberService.register(new NewMember(999L, "Bad 123", "john.smith@mailinator.com", "2125551234")))
			.isInstanceOf(MemberValidationException.class);
		then(memberPersistence).shouldHaveNoInteractions();
	}

	@Test
	void emailUniquenessIsCheckedBeforeTheId() {
		given(memberPersistence.existsByEmail("john.smith@mailinator.com")).willReturn(true);

		assertThatThrownBy(() -> memberService.register(new NewMember(999L, "Someone Else", "john.smith@mailinator.com", "2125551234")))
			.isInstanceOf(EmailAlreadyTakenException.class);
	}

	// the members page passes the browser language; messages captured from the legacy page with Accept-Language: fr
	@Test
	void registerWithALanguageGivesTheValidationMessagesInThatLanguage() {
		NewMember member = new NewMember(null, "Bad 123", "not-an-email", "12");

		assertThatThrownBy(() -> memberService.register(member, Locale.FRENCH))
			.isInstanceOfSatisfying(MemberValidationException.class,
					exception -> assertThat(exception.violations()).containsOnly(
							entry("name", "ne doit contenir que des lettres et des espaces"),
							entry("email", "doit être une adresse électronique syntaxiquement correcte"),
							entry("phoneNumber", "la taille doit être comprise entre 10 et 12")));
		then(memberPersistence).shouldHaveNoInteractions();
	}

	@Test
	void registerWithALanguageStoresAValidMemberLikeRegister() {
		NewMember jane = new NewMember(null, "Jane Doe", "jane.doe@example.com", "2125551234");
		Member stored = new Member(201L, "Jane Doe", "jane.doe@example.com", "2125551234");
		given(memberPersistence.existsByEmail("jane.doe@example.com")).willReturn(false);
		given(memberPersistence.create(jane)).willReturn(stored);

		assertThat(memberService.register(jane, Locale.FRENCH)).isEqualTo(stored);
	}

	@Test
	void registerWithALanguageRejectsAnEmailAlreadyTaken() {
		given(memberPersistence.existsByEmail("john.smith@mailinator.com")).willReturn(true);

		assertThatThrownBy(() -> memberService
			.register(new NewMember(null, "Someone Else", "john.smith@mailinator.com", "2125551234"), Locale.FRENCH))
			.isInstanceOf(EmailAlreadyTakenException.class);
		then(memberPersistence).should(never()).create(any());
	}

	/**
	 * A field breaking several rules gets one message: the first rule in the order written on the entity. Same as the
	 * legacy page for empty and non-numeric phones (captured live). Known difference: legacy's pick comes from
	 * Bean Validation's unordered result, so for a name both too long and with digits it shows the pattern message,
	 * and for a phone with 13 digits the digits message.
	 */
	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"empty phone (size + digits)            | Jane Doe                     | ''            | phoneNumber | size must be between 10 and 12",
			"non-numeric phone (size + digits)      | Jane Doe                     | 12a           | phoneNumber | size must be between 10 and 12",
			"too long name with digits (size + pattern) | Abcdefghijklmnopqrstuvwxyz 1 | 2125551234 | name        | size must be between 1 and 25" })
	void aFieldBreakingSeveralRulesReportsTheFirstRule(String scenario, String name, String phoneNumber, String field,
			String message) {
		assertThatThrownBy(() -> memberService.register(new NewMember(null, name, "a@example.com", phoneNumber),
				Locale.ENGLISH))
			.isInstanceOfSatisfying(MemberValidationException.class,
					exception -> assertThat(exception.violations()).containsExactly(entry(field, message)));
	}

	@Test
	void awaitReadableReturnsOnceCdcHasCopiedTheMember() {
		Member jane = new Member(201L, "Jane Doe", "jane.doe@example.com", "2125551234");
		given(memberPersistence.findById(201L)).willReturn(Optional.empty(), Optional.empty(), Optional.of(jane));

		assertThat(memberService.awaitReadable(201L, Duration.ofSeconds(5))).isTrue();
		then(memberPersistence).should(times(3)).findById(201L);
	}

	@Test
	void awaitReadableGivesUpAfterTheTimeout() {
		given(memberPersistence.findById(201L)).willReturn(Optional.empty());
		long start = System.nanoTime();

		assertThat(memberService.awaitReadable(201L, Duration.ofMillis(300))).isFalse();
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
	}

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
	void findAllOrderedByNameReturnsTheMembersInPersistenceOrder() {
		List<Member> members = List.of(new Member(2L, "alice", "alice@example.com", "2125550001"),
				new Member(1L, "Bob", "bob@example.com", "2125550002"));
		given(memberPersistence.findAllOrderedByName()).willReturn(members);

		assertThat(memberService.findAllOrderedByName()).containsExactlyElementsOf(members);
	}

	@Test
	void findAllOrderedByNameReturnsAnEmptyListWhenThereAreNoMembers() {
		given(memberPersistence.findAllOrderedByName()).willReturn(List.of());

		assertThat(memberService.findAllOrderedByName()).isEmpty();
	}

	@Test
	void synchronizeStoresTheMember() {
		Member member = new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234");

		memberService.synchronize(member);

		then(memberPersistence).should().synchronize(member);
	}

	@Test
	void removeDeletesTheMember() {
		memberService.remove(1L);

		then(memberPersistence).should().deleteById(1L);
	}

}
