package com.dublintech.mcpproto1.pet;

import com.dublintech.mcpproto1.common.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum PetStatus {
    AVAILABLE("available"),
    PENDING("pending"),
    SOLD("sold");

    private final String value;

    PetStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Parses a wire value such as {@code "available"}, rejecting unknown values with a 400.
     */
    public static PetStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Invalid status value: " + value));
    }
}
