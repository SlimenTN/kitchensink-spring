package com.migration.kitchensink.infrastructure.rest;

import com.migration.kitchensink.domaine.exceptions.MemberNotFoundException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// Legacy answers 404 with no body and no Content-Type, for unknown members and unknown paths alike
@RestControllerAdvice
public class RestExceptionHandler {

	@ExceptionHandler(MemberNotFoundException.class)
	public ResponseEntity<Void> memberNotFound() {
		return ResponseEntity.notFound().build();
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<Void> noResourceFound() {
		return ResponseEntity.notFound().build();
	}

}
