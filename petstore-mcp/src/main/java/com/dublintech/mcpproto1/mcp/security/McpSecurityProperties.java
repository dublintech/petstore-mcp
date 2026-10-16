package com.dublintech.mcpproto1.mcp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Transport-level security settings for the MCP endpoint ({@code petstore.mcp.*}).
 *
 * @param allowedOrigins browser origins allowed to call the MCP endpoint. This list is only for browser-based MCP
 *                       clients: the defaults are the MCP Inspector's web UI, which browsers may report as either
 *                       localhost or 127.0.0.1 depending on the URL typed. Non-browser clients send no Origin header
 *                       and never need an entry here; that includes Claude Desktop, whose route is
 *                       Claude Desktop → stdio → mcp-remote (Node.js) → HTTP → /mcp.
 */
@ConfigurationProperties("petstore.mcp")
public record McpSecurityProperties(
        @DefaultValue({"http://localhost:6274", "http://127.0.0.1:6274"}) List<String> allowedOrigins) {
}
