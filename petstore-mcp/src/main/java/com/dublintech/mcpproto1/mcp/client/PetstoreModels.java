package com.dublintech.mcpproto1.mcp.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The gateway's own view of the Petstore API's JSON. These deliberately duplicate the API's models instead
 * of sharing them: the gateway depends on the HTTP contract, not the API's code. They are tolerant readers
 * (unknown fields are ignored) and keep statuses as strings, so additions to the API don't break the gateway.
 */
public final class PetstoreModels {

    private PetstoreModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pet(Long id, Category category, String name, List<String> photoUrls, List<Tag> tags,
                      String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(Long id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(Long id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Order(Long id, Long petId, Integer quantity, OffsetDateTime shipDate, String status,
                        Boolean complete) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(Long id, String username, String firstName, String lastName, String email, String phone,
                       Integer userStatus) {
    }

    /**
     * The API's response envelope. For errors, {@code type} is a machine-readable code and field/rule/limit give
     * typed detail. {@code message} is free text for humans: the gateway logs it but never forwards it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiResponse(Integer code, String type, String message, String field, String rule, Integer limit) {
    }

    /** Request body for placing an order; the API fills in id, status and complete. */
    public record NewOrder(long petId, int quantity) {
    }
}
