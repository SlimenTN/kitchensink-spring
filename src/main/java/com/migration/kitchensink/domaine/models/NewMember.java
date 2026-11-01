package com.migration.kitchensink.domaine.models;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A member to register. Rules and messages are the legacy Member entity's (model/Member.java).
 *
 * @param id must be empty: ids are assigned on registration (legacy rejects a member sent with an id)
 */
public record NewMember(
		Long id,
		@NotNull @Size(min = 1, max = 25) @Pattern(regexp = "[^0-9]*", message = "{name_validation_message}") String name,
		@NotNull @NotEmpty @Email String email,
		@NotNull @Size(min = 10, max = 12) @Digits(fraction = 0, integer = 12) String phoneNumber) {

}
