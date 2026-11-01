package com.migration.kitchensink.infrastructure.web;

import com.migration.kitchensink.domaine.models.Member;

// one line of the members table on the page
public record MemberRow(Long id, String name, String email, String phoneNumber) {

	public static MemberRow from(Member member) {
		return new MemberRow(member.id(), member.name(), member.email(), member.phoneNumber());
	}

}
