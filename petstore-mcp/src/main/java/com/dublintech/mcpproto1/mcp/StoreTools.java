package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiClient;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Order;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Read-only store tools that map one-to-one onto Petstore API calls. Orders are placed through
 * {@link PetstoreFacadeTools#buyPet}.
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
        return api.getInventory();
    }

    @McpTool(name = "getOrderById", description = "Find a purchase order by its numeric ID.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Order getOrderById(@McpToolParam(description = "ID of the order, 1 or greater") long orderId) {
        return api.getOrder(orderId);
    }
}
