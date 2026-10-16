package com.dublintech.mcpproto1.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Generic response envelope from the Petstore spec ({@code #/definitions/ApiResponse}). For errors, {@code type}
 * carries the stable {@link ErrorCode} and {@code message} is free text for humans. The optional field, rule
 * and limit extend the spec additively and are omitted when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse(Integer code, String type, String message, String field, String rule, Integer limit) {

    public static ApiResponse ok(String message) {
        return new ApiResponse(200, "unknown", message, null, null, null);
    }

    public static ApiResponse error(int status, ErrorCode code, String message, FieldError fieldError) {
        return fieldError == null
                ? new ApiResponse(status, code.name(), message, null, null, null)
                : new ApiResponse(status, code.name(), message, fieldError.field(), fieldError.rule().name(),
                        fieldError.limit());
    }
}
