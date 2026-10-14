package com.dublintech.mcpproto1.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown by services to signal a spec-defined error response (400, 404, 405, ...).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException invalidInput(String message) {
        return new ApiException(HttpStatus.METHOD_NOT_ALLOWED, message);
    }
}