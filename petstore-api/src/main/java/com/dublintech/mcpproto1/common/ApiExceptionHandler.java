package com.dublintech.mcpproto1.common;

import com.dublintech.mcpproto1.common.FieldError.Rule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Renders errors as {@link ApiResponse} bodies so every endpoint fails in the same shape, with a stable code.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse> handleApiException(ApiException ex) {
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getFieldError());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PARAMETER,
                "Invalid value for '" + ex.getName() + "': " + ex.getValue(), FieldError.of(ex.getName(), Rule.TYPE));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_PARAMETER,
                "Missing required parameter '" + ex.getParameterName() + "'",
                FieldError.of(ex.getParameterName(), Rule.REQUIRED));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "Malformed request body", null);
    }

    private static ResponseEntity<ApiResponse> respond(HttpStatus status, ErrorCode code, String message,
                                                       FieldError fieldError) {
        return ResponseEntity.status(status).body(ApiResponse.error(status.value(), code, message, fieldError));
    }
}
