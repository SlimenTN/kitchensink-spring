package com.migration.kitchensink.domaine.exceptions;

public class MemberNotFoundException extends RuntimeException {

	public MemberNotFoundException(long id) {
		super("Member " + id + " not found");
	}

}
