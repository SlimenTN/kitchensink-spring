package com.migration.kitchensink.infrastructure.rest;

import java.util.List;

import com.migration.kitchensink.domaine.services.MemberService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rest/members")
public class MemberRestController {

	private final MemberService memberService;

	public MemberRestController(MemberService memberService) {
		this.memberService = memberService;
	}

	// All members, sorted by name like legacy
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public List<MemberResponse> listAllMembers() {
		return memberService.findAllOrderedByName().stream().map(MemberResponse::from).toList();
	}

	@GetMapping(value = "/{id:[0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
	public MemberResponse lookupMemberById(@PathVariable long id) {
		return MemberResponse.from(memberService.findById(id));
	}

}
