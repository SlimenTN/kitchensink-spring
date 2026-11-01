package com.migration.kitchensink.domaine.exceptions;

public class EmailAlreadyTakenException extends RuntimeException {

	public EmailAlreadyTakenException(String email) {
		super("Email already taken: " + email);
	}

}
