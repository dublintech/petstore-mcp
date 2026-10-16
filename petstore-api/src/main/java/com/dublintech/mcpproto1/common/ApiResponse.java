package com.dublintech.mcpproto1.common;

/**
 * Generic response envelope from the Petstore spec ({@code #/definitions/ApiResponse}).
 */
public record ApiResponse(Integer code, String type, String message) {

    public static ApiResponse ok(String message) {
        return new ApiResponse(200, "unknown", message);
    }

    public static ApiResponse error(int code, String message) {
        return new ApiResponse(code, "error", message);
    }
}