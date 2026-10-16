package com.dublintech.mcpproto1.mcp.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Connection settings for the Petstore REST API ({@code petstore.api.*}).
 */
@ConfigurationProperties("petstore.api")
public record PetstoreApiProperties(
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {
}
