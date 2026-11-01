package com.migration.kitchensink.domaine.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.migration.kitchensink.domaine.exceptions.EmailAlreadyTakenException;
import com.migration.kitchensink.domaine.exceptions.MemberIdNotAllowedException;
import com.migration.kitchensink.domaine.exceptions.MemberNotFoundException;
import com.migration.kitchensink.domaine.exceptions.MemberValidationException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import com.migration.kitchensink.infrastructure.database.MemberPersistence;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Service;

@Service
public class MemberService {

	private final MemberPersistence memberPersistence;

	private final Validator validator;

	public MemberService(
            final MemberPersistence memberPersistence,
			final Validator validator) {

		this.memberPersistence = memberPersistence;
		this.validator = validator;
	}

	/**
	 * Registers a member. Checks happen in the legacy order: field rules, then email uniqueness, then the id.
	 * @throws MemberValidationException when a field breaks a rule (one message per field)
	 * @throws EmailAlreadyTakenException when another member already has this email
	 * @throws MemberIdNotAllowedException when the member already carries an id
	 */
	public Member register(NewMember member) {
		Set<ConstraintViolation<NewMember>> violations = validator.validate(member);
		if (!violations.isEmpty()) {
			// like legacy: one message per field (the last one when a field breaks several rules)
			Map<String, String> messages = new HashMap<>();
			violations.forEach(violation -> messages.put(violation.getPropertyPath().toString(), violation.getMessage()));
			throw new MemberValidationException(messages);
		}
		if (memberPersistence.existsByEmail(member.email())) {
			throw new EmailAlreadyTakenException(member.email());
		}
		if (member.id() != null) {
			throw new MemberIdNotAllowedException(member.id());
		}
		return memberPersistence.create(member);
	}

	/**
	 * @throws MemberNotFoundException when no member has this id
	 */
	public Member findById(long id) {
		return memberPersistence.findById(id).orElseThrow(() -> new MemberNotFoundException(id));
	}

	/**
	 * All members, sorted by name like the legacy list (case- and accent-insensitive).
	 */
	public List<Member> findAllOrderedByName() {
		return memberPersistence.findAllOrderedByName();
	}

	/**
	 * Stores the member as it exists in the legacy system (created, updated or snapshotted).
	 */
	public void synchronize(Member member) {
		memberPersistence.synchronize(member);
	}

	/**
	 * Removes a member deleted from the legacy system; unknown ids are ignored.
	 */
	public void remove(long id) {
		memberPersistence.deleteById(id);
	}

}
