package com.payments.gateway.error;

import com.payments.gateway.error.Exceptions.DuplicateReference;
import com.payments.gateway.error.Exceptions.IdempotencyConflict;
import com.payments.gateway.error.Exceptions.InvalidSignature;
import com.payments.gateway.error.Exceptions.InvalidTransition;
import com.payments.gateway.error.Exceptions.LedgerPosting;
import com.payments.gateway.error.Exceptions.UnknownReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorBody(String code, String message) {
    }

    @ExceptionHandler(InvalidSignature.class)
    ResponseEntity<ErrorBody> unauthorized(DomainException e) {
        return body(HttpStatus.UNAUTHORIZED, e);
    }

    @ExceptionHandler({IdempotencyConflict.class, DuplicateReference.class, InvalidTransition.class})
    ResponseEntity<ErrorBody> conflict(DomainException e) {
        return body(HttpStatus.CONFLICT, e);
    }

    /** Covers the race where two requests insert the same reference; the unique constraint wins. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> constraint(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("CONSTRAINT_VIOLATION", "request conflicts with existing data"));
    }

    @ExceptionHandler(UnknownReference.class)
    ResponseEntity<ErrorBody> notFound(DomainException e) {
        return body(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(LedgerPosting.class)
    ResponseEntity<ErrorBody> unprocessable(DomainException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody("BAD_REQUEST", e.getMessage()));
    }

    private static ResponseEntity<ErrorBody> body(HttpStatus status, DomainException e) {
        return ResponseEntity.status(status).body(new ErrorBody(e.getCode(), e.getMessage()));
    }
}
