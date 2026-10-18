package com.dublintech.mcpproto1.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds {@code scope="petstore.read"} to the gateway's {@code 401} challenges (RFC 6750 section 3), so MCP clients log
 * in asking for reading only. Buying is approved later, the first time the user buys ({@link BuyStepUpFilter}).
 *
 * <p>The MCP spec has clients prefer a challenge's {@code scope} over {@code scopes_supported} in the metadata, which
 * lists both scopes. mcp-remote keeps this first scope for the rest of its run. Spring's entry point can't add a
 * scope itself, so the header is completed here as it is written.
 */
class ReadScopeChallengeFilter extends OncePerRequestFilter {

    private final String scope;

    ReadScopeChallengeFilter(String scope) {
        this.scope = scope;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                super.setHeader(name, withScope(name, value));
            }

            @Override
            public void addHeader(String name, String value) {
                super.addHeader(name, withScope(name, value));
            }
        });
    }

    /** Bearer challenges that name no scope yet, such as "no token" or "invalid_token", get the read scope. */
    private String withScope(String name, String value) {
        if (!HttpHeaders.WWW_AUTHENTICATE.equalsIgnoreCase(name) || value == null || !value.startsWith("Bearer")
                || value.contains("scope=")) {
            return value;
        }
        return value.equals("Bearer") ? "Bearer scope=\"" + scope + "\"" : value + ", scope=\"" + scope + "\"";
    }
}
