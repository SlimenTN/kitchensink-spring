package com.migration.kitchensink.infrastructure.web;

// the values shown in the registration form (kept after an error, emptied after a successful registration)
public record RegistrationForm(String name, String email, String phoneNumber) {

	public static RegistrationForm empty() {
		return new RegistrationForm("", "", "");
	}

}
