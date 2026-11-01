package com.migration.kitchensink.infrastructure.rest;

import java.util.List;
import java.util.Map;

import com.migration.kitchensink.domaine.exceptions.EmailAlreadyTakenException;
import com.migration.kitchensink.domaine.exceptions.MemberIdNotAllowedException;
import com.migration.kitchensink.domaine.exceptions.MemberNotFoundException;
import com.migration.kitchensink.domaine.exceptions.MemberValidationException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import com.migration.kitchensink.domaine.services.MemberService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemberRestController.class)
class MemberRestControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MemberService memberService;

	@Test
	void listsAllMembersInServiceOrderWithTheLegacyJsonContract() throws Exception {
		given(memberService.findAllOrderedByName())
			.willReturn(List.of(new Member(2L, "alice", "alice@example.com", "2125550001"),
					new Member(1L, "Bob", "bob@example.com", "2125550002")));

		mockMvc.perform(get("/rest/members").accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			// STRICT: same fields and types, and the array order is checked too
			.andExpect(content().json("""
					[
					  {"email": "alice@example.com", "id": 2, "name": "alice", "phoneNumber": "2125550001"},
					  {"email": "bob@example.com", "id": 1, "name": "Bob", "phoneNumber": "2125550002"}
					]
					""", JsonCompareMode.STRICT));
	}

	@Test
	void listsAnEmptyArrayWhenThereAreNoMembers() throws Exception {
		given(memberService.findAllOrderedByName()).willReturn(List.of());

		mockMvc.perform(get("/rest/members").accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().json("[]", JsonCompareMode.STRICT));
	}

	private static final String JANE = """
			{"name": "Jane Doe", "email": "jane.doe@example.com", "phoneNumber": "2125551234"}""";

	@Test
	void createRegistersTheMemberAndAnswersLikeLegacy() throws Exception {
		NewMember jane = new NewMember(null, "Jane Doe", "jane.doe@example.com", "2125551234");
		given(memberService.register(jane)).willReturn(new Member(201L, "Jane Doe", "jane.doe@example.com", "2125551234"));

		mockMvc.perform(post("/rest/members").contentType(MediaType.APPLICATION_JSON).content(JANE))
			.andExpect(status().isOk())
			.andExpect(content().string(""))
			.andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE));
		then(memberService).should().register(jane);
	}

	@Test
	void createPassesAnIdSentByTheClientToTheDomain() throws Exception {
		NewMember withId = new NewMember(999L, "Jane Doe", "jane.doe@example.com", "2125551234");
		given(memberService.register(withId)).willThrow(new MemberIdNotAllowedException(999L));

		mockMvc.perform(post("/rest/members").contentType(MediaType.APPLICATION_JSON).content("""
				{"id": 999, "name": "Jane Doe", "email": "jane.doe@example.com", "phoneNumber": "2125551234"}"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			// legacy leaks this Hibernate message as is; kept identical during the migration
			.andExpect(content().json("""
					{"error": "jakarta.persistence.EntityExistsException: detached entity passed to persist: org.jboss.as.quickstarts.kitchensink.model.Member"}
					""", JsonCompareMode.STRICT));
	}

	@Test
	void createAnswersAnInvalidMemberWithOneMessagePerField() throws Exception {
		given(memberService.register(any())).willThrow(new MemberValidationException(
				Map.of("name", "must contain only letters and spaces", "phoneNumber", "size must be between 10 and 12")));

		mockMvc.perform(post("/rest/members").contentType(MediaType.APPLICATION_JSON).content(JANE))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().json("""
					{"name": "must contain only letters and spaces", "phoneNumber": "size must be between 10 and 12"}
					""", JsonCompareMode.STRICT));
	}

	@Test
	void createAnswersAnEmailAlreadyTakenWith409() throws Exception {
		given(memberService.register(any())).willThrow(new EmailAlreadyTakenException("jane.doe@example.com"));

		mockMvc.perform(post("/rest/members").contentType(MediaType.APPLICATION_JSON).content(JANE))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().json("{\"email\": \"Email taken\"}", JsonCompareMode.STRICT));
	}

	@Test
	void createAnswersMalformedJsonLikeLegacy() throws Exception {
		mockMvc.perform(post("/rest/members").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			// legacy: plain-text parser error behind a JSON content type
			.andExpect(content().string(startsWith("RESTEASY008200: JSON Binding deserialization error: ")));
		verifyNoInteractions(memberService);
	}

	@Test
	void returnsTheMemberWithJsonContract() throws Exception {
		given(memberService.findById(1L)).willReturn(new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234"));

		mockMvc.perform(get("/rest/members/1").accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			// STRICT: exactly these fields, with these names and JSON types, nothing more
			.andExpect(content().json("""
					{"email": "jane.doe@example.com", "id": 1, "name": "Jane Doe", "phoneNumber": "2125551234"}
					""", JsonCompareMode.STRICT));
	}

	@Test
	void unknownMemberReturnsBare404() throws Exception {
		given(memberService.findById(99L)).willThrow(new MemberNotFoundException(99L));

		mockMvc.perform(get("/rest/members/99").accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isNotFound())
			.andExpect(content().string(""))
			.andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE));
	}

	@Test
	void nonNumericIdReturnsBare404() throws Exception {
		mockMvc.perform(get("/rest/members/abc").accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isNotFound())
			.andExpect(content().string(""))
			.andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE));

		verifyNoInteractions(memberService);
	}

}
