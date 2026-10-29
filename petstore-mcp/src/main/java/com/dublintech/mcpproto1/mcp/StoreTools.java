package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiClient;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Order;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only store tools that map one-to-one onto Petstore API calls. Orders are placed through
 * {@link PetstoreFacadeTools#buyPet}. The API shows each user only their own orders, so these tools need no check
 * of their own: the user comes from the token the gateway exchanged for this request.
 */
@Component
public class StoreTools {

    private final PetstoreApiClient api;

    public StoreTools(PetstoreApiClient api) {
        this.api = api;
    }

    @McpTool(name = "getInventory", description = "Count the pets in the store per status (available, pending, sold).",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Integer> getInventory() {
        return GatewayErrors.call(() -> api.getInventory());
    }

    public record MyOrders(int count, List<Order> orders, String note) {
    }

    @McpTool(name = "listMyOrders", description = "List the signed-in user's purchase orders, oldest first.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public MyOrders listMyOrders() {
        List<Order> orders = GatewayErrors.call(() -> api.getMyOrders());
        return new MyOrders(orders.size(), orders, orders.isEmpty() ? "No orders yet. Use buyPet to buy a pet." : null);
    }

    @McpTool(name = "getOrderById", description = "Find one of the signed-in user's purchase orders by its numeric ID.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Order getOrderById(@McpToolParam(description = "ID of the order, 1 or greater") long orderId) {
        return GatewayErrors.call(() -> api.getOrder(orderId));
    }
}
