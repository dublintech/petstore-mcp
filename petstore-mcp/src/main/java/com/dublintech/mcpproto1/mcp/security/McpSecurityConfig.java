package com.dublintech.mcpproto1.mcp.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the MCP endpoint's transport-level protections.
 *
 * <p>The MCP SDK has its own {@code DefaultServerTransportSecurityValidator}, and the WebMVC transport accepts one,
 * but Spring AI 2.0.1's auto-configuration never sets it, leaving the no-op validator in place. A servlet filter
 * adds the check without replacing Spring AI's transport bean.
 */
@Configuration
@EnableConfigurationProperties(McpSecurityProperties.class)
public class McpSecurityConfig {

    @Bean
    FilterRegistrationBean<OriginValidationFilter> originValidationFilter(
            McpSecurityProperties properties,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint) {
        FilterRegistrationBean<OriginValidationFilter> registration =
                new FilterRegistrationBean<>(new OriginValidationFilter(properties.allowedOrigins()));
        registration.addUrlPatterns(mcpEndpoint, mcpEndpoint + "/*");
        // Run before anything else touches the request.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
