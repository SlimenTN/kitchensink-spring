package com.migration.kitchensink.infrastructure.rest;

import com.migration.kitchensink.domaine.models.Member;

// Components in alphabetical order, like the legacy JSON: {"email", "id", "name", "phoneNumber"}
public record MemberResponse(String email, Long id, String name, String phoneNumber) {

	public static MemberResponse from(Member member) {
		return new MemberResponse(member.email(), member.id(), member.name(), member.phoneNumber());
	}

}
