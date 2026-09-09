package com.forgesphere.mcpgen.web;

import com.forgesphere.mcpgen.dto.Dtos.Envelope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Translates exceptions into the same `Envelope{success,data,error}`
 * shape the happy path uses. Keeps the client-side error handling
 * uniform.
 */
@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Envelope<Object>> duplicate(org.springframework.dao.DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Envelope.fail("This project/version already exists. Refresh and choose a new version."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Envelope<Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Envelope.fail(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Envelope<Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst().orElse("validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Envelope.fail(msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Envelope<Object>> generic(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Envelope.fail(e.getMessage()));
    }
}
