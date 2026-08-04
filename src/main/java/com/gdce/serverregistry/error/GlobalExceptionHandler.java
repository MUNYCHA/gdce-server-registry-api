package com.gdce.serverregistry.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(Instant timestamp, int status, String message, List<String> errors) {

        static ErrorResponse of(HttpStatus status, String message) {
            return new ErrorResponse(Instant.now(), status.value(), message, null);
        }

        static ErrorResponse validation(List<String> errors) {
            return new ErrorResponse(Instant.now(), HttpStatus.BAD_REQUEST.value(), "Validation failed", errors);
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = Stream.concat(
                        ex.getBindingResult().getFieldErrors().stream()
                                .map(this::describe),
                        ex.getBindingResult().getGlobalErrors().stream()
                                .map(error -> error.getObjectName() + ": " + error.getDefaultMessage()))
                .sorted()
                .toList();
        return ResponseEntity.badRequest().body(ErrorResponse.validation(errors));
    }

    /**
     * Jackson rejects the body before validation runs — a wrong JSON type such as a
     * non-numeric port. Handled here so every 4xx shares the response shape.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(HttpStatus.BAD_REQUEST, "Malformed request body"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConflict(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT, "A server with this IP and port already exists"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
