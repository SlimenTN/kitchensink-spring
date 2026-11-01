package com.migration.kitchensink.domaine.exceptions;

// A member sent for registration already carries an id (ids are assigned on registration)
public class MemberIdNotAllowedException extends RuntimeException {

	public MemberIdNotAllowedException(long id) {
		super("A new member must not have an id, got " + id);
	}

}
