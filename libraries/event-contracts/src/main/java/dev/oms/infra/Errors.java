package dev.oms.infra;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(org.springframework.dao.EmptyResultDataAccessException.class)
    ResponseEntity<ProblemDetail> missing() {
        return ResponseEntity.status(404)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource is not available"));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            IllegalStateException.class,
            org.springframework.dao.DataIntegrityViolationException.class
    })
    ResponseEntity<ProblemDetail> conflict(Exception e) {
        var p =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        e instanceof org.springframework.dao.DataIntegrityViolationException
                                ? "Conflicting operation"
                                : e.getMessage());
        return ResponseEntity.status(409).body(p);
    }
}
