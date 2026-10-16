package com.dublintech.mcpproto1.mcp.client;

/**
 * A failed call to the Petstore API: either an HTTP error response or the API being unreachable.
 */
public class PetstoreApiException extends RuntimeException {

    /** Status used when the API could not be reached at all. */
    public static final int UNAVAILABLE = 503;

    private final int status;

    public PetstoreApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public PetstoreApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = UNAVAILABLE;
    }

    public int getStatus() {
        return status;
    }

    public boolean isNotFound() {
        return status == 404;
    }
}
