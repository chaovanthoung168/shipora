package com.shipora.project.adapter.in.web.exception;

import com.shipora.project.domain.exception.ProjectException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ProjectExceptionHandler {

    @ExceptionHandler(ProjectException.NotFound.class)
    public ProblemDetail notFound(
            ProjectException.NotFound exception
    ) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
    }

    @ExceptionHandler(ProjectException.DuplicateSlug.class)
    public ProblemDetail duplicateSlug(
            ProjectException.DuplicateSlug exception
    ) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
    }

    @ExceptionHandler(ProjectException.Invalid.class)
    public ProblemDetail invalidProject(
            ProjectException.Invalid exception
    ) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validationFailed(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> errors = new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.putIfAbsent(
                        error.getField(),
                        error.getDefaultMessage() == null
                                ? "Invalid value."
                                : error.getDefaultMessage()
                ));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request fields are invalid."
        );

        problem.setProperty("errors", errors);

        return problem;
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ProblemDetail malformedRequest(Exception exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Invalid request. Check the JSON body, UUIDs, "
                        + "and pagination parameters."
        );
    }
}