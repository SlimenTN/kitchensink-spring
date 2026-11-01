package com.migration.kitchensink.infrastructure.rest;

import java.util.Map;

import com.migration.kitchensink.domaine.exceptions.EmailAlreadyTakenException;
import com.migration.kitchensink.domaine.exceptions.MemberIdNotAllowedException;
import com.migration.kitchensink.domaine.exceptions.MemberNotFoundException;
import com.migration.kitchensink.domaine.exceptions.MemberValidationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Error responses identical to the legacy REST API (MemberResourceRESTService). During the migration the new
 * system answers exactly like legacy, including legacy's leaked error messages; improvements come after cutover.
 */
@RestControllerAdvice
public class RestExceptionHandler {

	// legacy: persisting a Member that already has an id fails in Hibernate, and the message is returned as is
	static final String ID_NOT_ALLOWED_ERROR = "jakarta.persistence.EntityExistsException: detached entity passed to persist: org.jboss.as.quickstarts.kitchensink.model.Member";

	// legacy (RESTEasy JSON-B) prefixes the parser error with this code and declares a JSON content type
	static final String MALFORMED_JSON_ERROR_PREFIX = "RESTEASY008200: JSON Binding deserialization error: ";

	// Legacy answers 404 with no body and no Content-Type, for unknown members and unknown paths alike
	@ExceptionHandler(MemberNotFoundException.class)
	public ResponseEntity<Void> memberNotFound() {
		return ResponseEntity.notFound().build();
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<Void> noResourceFound() {
		return ResponseEntity.notFound().build();
	}

	// 400 {"field": "message", ...}
	@ExceptionHandler(MemberValidationException.class)
	public ResponseEntity<Map<String, String>> invalidMember(MemberValidationException exception) {
		return ResponseEntity.badRequest().body(exception.violations());
	}

	// 409 {"email": "Email taken"}
	@ExceptionHandler(EmailAlreadyTakenException.class)
	public ResponseEntity<Map<String, String>> emailTaken() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("email", "Email taken"));
	}

	// 400 {"error": ID_NOT_ALLOWED_ERROR}
	@ExceptionHandler(MemberIdNotAllowedException.class)
	public ResponseEntity<Map<String, String>> idNotAllowed() {
		return ResponseEntity.badRequest().body(Map.of("error", ID_NOT_ALLOWED_ERROR));
	}

	// 500, Content-Type application/json, plain-text body starting with MALFORMED_JSON_ERROR_PREFIX
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<String> malformedJson(HttpMessageNotReadableException exception) {
		return ResponseEntity.internalServerError()
			.contentType(MediaType.APPLICATION_JSON)
			.body(MALFORMED_JSON_ERROR_PREFIX + exception.getMostSpecificCause().getMessage());
	}

}
