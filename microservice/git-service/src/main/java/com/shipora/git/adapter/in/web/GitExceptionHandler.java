package com.shipora.git.adapter.in.web;

import com.shipora.git.domain.GitException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {GitController.class, GitHubSetupController.class})
public class GitExceptionHandler {
    @ExceptionHandler(GitException.class)
    public ResponseEntity<ProblemDetail> git(GitException exception) {
        HttpStatus status = switch (exception.getKind()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case PROVIDER -> HttpStatus.BAD_GATEWAY;
            case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
        };
        return problem(status, exception.getMessage());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request body or parameters.");
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> database(DataAccessException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Git storage is temporarily unavailable.");
    }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
