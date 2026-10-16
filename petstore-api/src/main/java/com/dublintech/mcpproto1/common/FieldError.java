package com.dublintech.mcpproto1.common;

/**
 * Typed detail for a validation error: which field broke which rule. Unlike the free-text message, every value
 * here is a known name, an enum or a number, so clients can act on it without trusting arbitrary text.
 */
public record FieldError(String field, Rule rule, Integer limit) {

    public enum Rule {
        REQUIRED,
        ONE_OF,
        MIN,
        MUST_EXIST,
        TYPE,
        UNIQUE
    }

    public static FieldError of(String field, Rule rule) {
        return new FieldError(field, rule, null);
    }

    public static FieldError min(String field, int limit) {
        return new FieldError(field, Rule.MIN, limit);
    }
}
