package com.dublintech.mcpproto1.common;

/**
 * Stable, machine-readable error codes, sent in {@link ApiResponse#type()}. Clients should branch on these;
 * the accompanying message is free text for human readers and may change or echo input.
 */
public enum ErrorCode {
    PET_NOT_FOUND,
    ORDER_NOT_FOUND,
    USER_NOT_FOUND,
    INVALID_ID,
    INVALID_STATUS,
    INVALID_TAG,
    INVALID_PET,
    INVALID_ORDER,
    INVALID_USER,
    USERNAME_TAKEN,
    INVALID_CREDENTIALS,
    MISSING_PARAMETER,
    INVALID_PARAMETER,
    MALFORMED_REQUEST
}
