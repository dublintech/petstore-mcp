package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiException;

import java.util.function.Supplier;

/**
 * Turns Petstore API failures into messages the gateway wrote itself. Messages are chosen by error code, and may
 * include only values the gateway has checked (known field names, rules, numbers) plus a reference ID for the logs.
 * Text from the API never reaches the model: an unknown code falls back to a generic message for its kind.
 */
public final class GatewayErrors {

    private GatewayErrors() {
    }

    /** Runs an API call, translating any failure into a {@link GatewayErrorException}. */
    public static <T> T call(Supplier<T> apiCall) {
        try {
            return apiCall.get();
        } catch (PetstoreApiException ex) {
            throw translate(ex);
        }
    }

    public static GatewayErrorException translate(PetstoreApiException ex) {
        return new GatewayErrorException(message(ex) + " (ref " + ex.getReference() + ")");
    }

    private static String message(PetstoreApiException ex) {
        String code = ex.getErrorCode() == null ? "" : ex.getErrorCode();
        return switch (code) {
            case "PET_NOT_FOUND" -> "No such pet. Use searchPets to find pets.";
            case "ORDER_NOT_FOUND" -> "No such order.";
            case "USER_NOT_FOUND" -> "No such user.";
            case "INVALID_STATUS" -> "Invalid pet status. Use one of: available, pending, sold.";
            case "INVALID_ID" -> "Invalid ID. IDs are whole numbers of 1 or more.";
            case "INVALID_TAG" -> "Invalid tag. Give at least one non-empty tag name.";
            default -> ex.getField() != null && ex.getRule() != null ? fieldMessage(ex) : kindMessage(ex);
        };
    }

    private static String fieldMessage(PetstoreApiException ex) {
        String problem = switch (ex.getRule()) {
            case REQUIRED -> "is required";
            case ONE_OF -> "must be one of the allowed values";
            case MIN -> ex.getLimit() == null ? "is too small" : "must be at least " + ex.getLimit();
            case MUST_EXIST -> "must refer to something that exists";
            case TYPE -> "has the wrong type";
            case UNIQUE -> "is already taken";
        };
        return "The Petstore rejected the request: '%s' %s. Fix the arguments and try again."
                .formatted(ex.getField(), problem);
    }

    private static String kindMessage(PetstoreApiException ex) {
        return switch (ex.getKind()) {
            case BAD_REQUEST -> "The Petstore rejected the request as invalid. Check the arguments and try again.";
            case NOT_ALLOWED -> "The user hasn't allowed this. Buying needs the petstore.buy permission, which the "
                    + "user grants on the Petstore login page. Nothing was changed. Don't retry; tell the user.";
            case NOT_FOUND -> "The Petstore couldn't find what was asked for.";
            case UNAVAILABLE -> "The Petstore is temporarily unavailable. Don't retry now; tell the user to try again shortly.";
            case SERVER_ERROR, BAD_RESPONSE ->
                    "The Petstore had an internal problem and couldn't complete the request. Tell the user to try again later.";
        };
    }
}
