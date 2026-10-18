package com.shipora.project.domain.exception;

import java.util.UUID;

public class ProjectException extends RuntimeException {
    public ProjectException(String message) {
        super(message);
    }

    public static final class Invalid extends ProjectException {

        public Invalid(String message) {
            super(message);
        }
    }

    public static final class NotFound extends ProjectException {

        public NotFound(UUID projectId) {
            super("Project not found: " + projectId);
        }
    }

    public static final class DuplicateSlug extends ProjectException {

        public DuplicateSlug() {
            super("A project with this slug already exists in your organization.");
        }
    }

}
