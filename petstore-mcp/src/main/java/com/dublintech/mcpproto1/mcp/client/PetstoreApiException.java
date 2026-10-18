package com.dublintech.mcpproto1.mcp.client;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A failed call to the Petstore API, reduced to values the gateway can trust: the HTTP status, a well-formed
 * error code, and field/rule/limit detail only when they match what the gateway knows. The API's free-text
 * message is never stored here (the client logs it under {@link #getReference()}), and there is no cause chain,
 * so nothing from the API can leak through {@link #getMessage()}.
 */
public class PetstoreApiException extends RuntimeException {

    public enum Kind {
        BAD_REQUEST,
        /** 401 or 403: the user's token doesn't allow this, most often a purchase without petstore.buy. */
        NOT_ALLOWED,
        NOT_FOUND,
        UNAVAILABLE,
        SERVER_ERROR,
        BAD_RESPONSE
    }

    public enum Rule {
        REQUIRED,
        ONE_OF,
        MIN,
        MUST_EXIST,
        TYPE,
        UNIQUE
    }

    /** Field names the gateway knows from the API contract; any other value is dropped. */
    private static final Set<String> KNOWN_FIELDS =
            Set.of("id", "petId", "orderId", "username", "name", "photoUrls", "status", "tags", "quantity");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final int status;
    private final Kind kind;
    private final String errorCode;
    private final String field;
    private final Rule rule;
    private final Integer limit;
    private final String reference;

    /** Builds an exception from an error response, keeping only values that pass validation. */
    public PetstoreApiException(int status, String errorCode, String field, String rule, Integer limit) {
        this(status, kindFor(status), errorCode, field, rule, limit);
    }

    private PetstoreApiException(int status, Kind kind, String errorCode, String field, String rule, Integer limit) {
        this(status, kind, errorCode, field, rule, limit, UUID.randomUUID().toString().substring(0, 8));
    }

    private PetstoreApiException(int status, Kind kind, String errorCode, String field, String rule, Integer limit,
                                 String reference) {
        super("Petstore API error (HTTP %d, ref %s)".formatted(status, reference));
        this.status = status;
        this.kind = kind;
        this.errorCode = errorCode != null && ERROR_CODE.matcher(errorCode).matches() ? errorCode : null;
        this.field = field != null && KNOWN_FIELDS.contains(field) ? field : null;
        this.rule = this.field == null ? null : parseRule(rule);
        this.limit = this.rule == Rule.MIN ? limit : null;
        this.reference = reference;
    }

    /** The API could not be reached (connection refused, timeout). */
    public static PetstoreApiException unavailable() {
        return new PetstoreApiException(503, Kind.UNAVAILABLE, null, null, null, null);
    }

    /** The API answered, but with something the gateway could not read. */
    public static PetstoreApiException badResponse() {
        return new PetstoreApiException(502, Kind.BAD_RESPONSE, null, null, null, null);
    }

    public int getStatus() {
        return status;
    }

    public Kind getKind() {
        return kind;
    }

    /** The API's error code if it was well-formed, otherwise null. */
    public String getErrorCode() {
        return errorCode;
    }

    /** The invalid field if it is one the gateway knows, otherwise null. */
    public String getField() {
        return field;
    }

    public Rule getRule() {
        return rule;
    }

    public Integer getLimit() {
        return limit;
    }

    /** Short ID linking a user-visible message to the gateway log entry with the full detail. */
    public String getReference() {
        return reference;
    }

    public boolean isNotFound() {
        return kind == Kind.NOT_FOUND;
    }

    private static Kind kindFor(int status) {
        if (status == 404) {
            return Kind.NOT_FOUND;
        }
        if (status == 401 || status == 403) {
            return Kind.NOT_ALLOWED;
        }
        if (status == 503) {
            return Kind.UNAVAILABLE;
        }
        return status >= 500 ? Kind.SERVER_ERROR : Kind.BAD_REQUEST;
    }

    private static Rule parseRule(String value) {
        return Arrays.stream(Rule.values()).filter(rule -> rule.name().equals(value)).findFirst().orElse(null);
    }
}
