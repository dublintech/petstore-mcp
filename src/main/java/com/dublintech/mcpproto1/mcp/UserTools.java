package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.user.User;
import com.dublintech.mcpproto1.user.UserService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Exposes the user operations as MCP tools, delegating to the same {@link UserService} as the REST API.
 * Login/logout are left out: they carry no meaning for a tool caller.
 */
@Component
public class UserTools {

    private final UserService userService;

    public UserTools(UserService userService) {
        this.userService = userService;
    }

    @McpTool(name = "getUserByName", description = "Look up a user by username. The password is never returned.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public User getUserByName(@McpToolParam(description = "Username, e.g. user1") String username) {
        return userService.getUserByName(username);
    }

    @McpTool(name = "createUser", description = "Create a user account and return it with its assigned ID.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public User createUser(
            @McpToolParam(description = "Unique username") String username,
            @McpToolParam(description = "First name", required = false) String firstName,
            @McpToolParam(description = "Last name", required = false) String lastName,
            @McpToolParam(description = "Email address", required = false) String email,
            @McpToolParam(description = "Password", required = false) String password,
            @McpToolParam(description = "Phone number", required = false) String phone) {
        return userService.createUser(new User(null, username, firstName, lastName, email, password, phone, 0));
    }

    @McpTool(name = "deleteUser", description = "Delete a user account.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true,
                    openWorldHint = false))
    public String deleteUser(@McpToolParam(description = "Username to delete") String username) {
        userService.deleteUser(username);
        return "Deleted user " + username;
    }
}
