package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiClient;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.User;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only user lookup. Account changes are not exposed over MCP.
 */
@Component
public class UserTools {

    private final PetstoreApiClient api;

    public UserTools(PetstoreApiClient api) {
        this.api = api;
    }

    @McpTool(name = "getUserByName", description = "Look up a user by username. The password is never returned.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public User getUserByName(@McpToolParam(description = "Username, e.g. user1") String username) {
        return api.getUser(username);
    }
}
