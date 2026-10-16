package com.dublintech.mcpproto1.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP gateway for the Petstore: a separate process that serves MCP on {@code /mcp} and reaches the
 * Petstore only through its REST API.
 */
@SpringBootApplication
public class PetstoreMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(PetstoreMcpApplication.class, args);
    }
}
