package com.shipora.project.domain.exception;

import java.util.UUID;

public abstract class ServerException extends RuntimeException {
    protected ServerException(String message) {
        super(message);
    }

    public static final class Invalid extends ServerException {
        public Invalid(String message) {
            super(message);
        }
    }

    public static final class NotFound extends ServerException {
        public NotFound(UUID id) {
            super("Server not found: " + id);
        }
    }

    public static final class CheckBusy extends ServerException {
        public CheckBusy() {
            super("An SSH check is already running or the check capacity is busy. Retry shortly.");
        }
    }
}
