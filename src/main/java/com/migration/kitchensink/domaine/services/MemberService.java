package com.migration.kitchensink.domaine.services;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.metadata.ConstraintDescriptor;

import org.springframework.stereotype.Service;

@Service
public class MemberService {

	// Bean Validation's own message resolution (ValidationMessages*.properties + built-in translations), for any language
	private static final MessageInterpolator MESSAGES = Validation.byDefaultProvider()
		.configure()
		.getDefaultMessageInterpolator();

	private static final Duration READABLE_POLL_INTERVAL = Duration.ofMillis(100);

	/**
	 * The rules in the order they are written on NewMember (and on the legacy Member entity). Bean Validation reports
	 * broken rules unordered; legacy shows one per field, picked by that unordered result. This order is deterministic
	 * and gives the same message as legacy for empty fields and single-rule errors.
	 */
	private static final List<Class<? extends Annotation>> RULE_ORDER = List.of(NotNull.class, NotEmpty.class,
			Size.class, Pattern.class, Email.class, Digits.class);

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
		// like the legacy REST API: messages in the server language
		return register(member, Locale.getDefault());
	}

	/**
	 * Same as {@link #register(NewMember)}, with validation messages in the given language (the members page uses
	 * the browser language, like the legacy page; the REST API uses the server language).
	 */
	public Member register(NewMember member, Locale locale) {
		Set<ConstraintViolation<NewMember>> violations = validator.validate(member);
		if (!violations.isEmpty()) {
			// like legacy: one message per field; when a field breaks several rules, the first one in RULE_ORDER
			Map<String, ConstraintViolation<NewMember>> firstPerField = new HashMap<>();
			violations.forEach(violation -> firstPerField.merge(violation.getPropertyPath().toString(), violation,
					(current, other) -> rank(other) < rank(current) ? other : current));
			Map<String, String> messages = new HashMap<>();
			firstPerField.forEach((field, violation) -> messages.put(field,
					MESSAGES.interpolate(violation.getMessageTemplate(), new ViolationContext(violation), locale)));
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
	 * Waits until a member just registered can be read (CDC copies it from the legacy database to MongoDB ~1 s
	 * later), so a page can show it right away like the legacy page does.
	 * @return false if it is still not readable after the timeout
	 */
	public boolean awaitReadable(long id, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (memberPersistence.findById(id).isEmpty()) {
			if (System.nanoTime() >= deadline) {
				return false;
			}
			try {
				Thread.sleep(READABLE_POLL_INTERVAL.toMillis());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
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

	private static int rank(ConstraintViolation<?> violation) {
		int rank = RULE_ORDER.indexOf(violation.getConstraintDescriptor().getAnnotation().annotationType());
		return rank < 0 ? Integer.MAX_VALUE : rank;
	}

	// what the message interpolator needs to fill in a template ({min}, {max}...): the violated constraint
	private record ViolationContext(ConstraintViolation<?> violation) implements MessageInterpolator.Context {

		@Override
		public ConstraintDescriptor<?> getConstraintDescriptor() {
			return violation.getConstraintDescriptor();
		}

		@Override
		public Object getValidatedValue() {
			return violation.getInvalidValue();
		}

		@Override
		public <T> T unwrap(Class<T> type) {
			throw new ValidationException("Unsupported context type " + type.getName());
		}

	}

}
