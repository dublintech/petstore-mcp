package com.dublintech.mcpproto1.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown by services to signal a spec-defined error response (400, 404, 405, ...), carrying a stable
 * {@link ErrorCode} and, for validation errors, a typed {@link FieldError}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;
    private final FieldError fieldError;

    public ApiException(HttpStatus status, ErrorCode code, String message, FieldError fieldError) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldError = fieldError;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getCode() {
        return code;
    }

    public FieldError getFieldError() {
        return fieldError;
    }

    public static ApiException badRequest(ErrorCode code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, null);
    }

    public static ApiException badRequest(ErrorCode code, String message, FieldError fieldError) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, fieldError);
    }

    public static ApiException notFound(ErrorCode code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message, null);
    }

    public static ApiException invalidInput(ErrorCode code, String message, FieldError fieldError) {
        return new ApiException(HttpStatus.METHOD_NOT_ALLOWED, code, message, fieldError);
    }
}
