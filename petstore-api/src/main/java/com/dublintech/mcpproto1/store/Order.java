package com.dublintech.mcpproto1.store;

import java.time.OffsetDateTime;

public record Order(
        Long id,
        Long petId,
        Integer quantity,
        OffsetDateTime shipDate,
        OrderStatus status,
        Boolean complete) {

    public Order withId(Long newId) {
        return new Order(newId, petId, quantity, shipDate, status, complete);
    }

    /** Fills in the defaults for fields the client left out. */
    public Order withDefaults() {
        return new Order(id, petId, quantity, shipDate,
                status == null ? OrderStatus.PLACED : status,
                complete == null ? Boolean.FALSE : complete);
    }
}