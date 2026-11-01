package com.migration.kitchensink.infrastructure.rest;

import com.migration.kitchensink.domaine.models.NewMember;

// JSON body of POST /rest/members; "id" is accepted like in legacy, and rejected by the domain
public record CreateMemberRequest(Long id, String name, String email, String phoneNumber) {

	public NewMember toNewMember() {
		return new NewMember(id, name, email, phoneNumber);
	}

}
