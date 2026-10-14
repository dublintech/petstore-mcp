package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.common.ApiException;
import com.dublintech.mcpproto1.store.Order;
import com.dublintech.mcpproto1.store.StoreService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Exposes the store operations as MCP tools, delegating to the same {@link StoreService} as the REST API.
 */
@Component
public class StoreTools {

    private final StoreService storeService;

    public StoreTools(StoreService storeService) {
        this.storeService = storeService;
    }

    @McpTool(name = "getInventory", description = "Count the pets in the store per status (available, pending, sold).",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Integer> getInventory() {
        return storeService.getInventory();
    }

    @McpTool(name = "placeOrder", description = "Place an order to buy a pet and return the new order.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public Order placeOrder(
            @McpToolParam(description = "ID of the pet to order") long petId,
            @McpToolParam(description = "Quantity, at least 1") int quantity,
            @McpToolParam(description = "Ship date as ISO-8601, e.g. 2026-11-01T10:00:00Z", required = false)
            String shipDate) {
        return storeService.placeOrder(new Order(null, petId, quantity, parseShipDate(shipDate), null, null));
    }

    @McpTool(name = "getOrderById", description = "Find a purchase order by its numeric ID.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Order getOrderById(@McpToolParam(description = "ID of the order, 1 or greater") long orderId) {
        return storeService.getOrderById(orderId);
    }

    @McpTool(name = "deleteOrder", description = "Delete a purchase order.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true,
                    openWorldHint = false))
    public String deleteOrder(@McpToolParam(description = "ID of the order, 1 or greater") long orderId) {
        storeService.deleteOrder(orderId);
        return "Deleted order " + orderId;
    }

    private static OffsetDateTime parseShipDate(String shipDate) {
        if (shipDate == null || shipDate.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(shipDate);
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest("Invalid shipDate, expected ISO-8601: " + shipDate);
        }
    }
}
