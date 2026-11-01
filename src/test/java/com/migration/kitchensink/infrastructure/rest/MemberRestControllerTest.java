package com.migration.kitchensink.infrastructure.rest;

import java.util.List;

import com.migration.kitchensink.domaine.exceptions.MemberNotFoundException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.services.MemberService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
