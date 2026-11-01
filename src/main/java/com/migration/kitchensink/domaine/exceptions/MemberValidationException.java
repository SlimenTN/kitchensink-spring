package com.migration.kitchensink.domaine.exceptions;

import java.util.Map;

public class MemberValidationException extends RuntimeException {

	private final Map<String, String> violations;

	/**
	 * @param violations one message per invalid field
	 */
	public MemberValidationException(Map<String, String> violations) {
		super("Invalid member: " + violations);
		this.violations = Map.copyOf(violations);
	}

	public Map<String, String> violations() {
		return violations;
	}

}
