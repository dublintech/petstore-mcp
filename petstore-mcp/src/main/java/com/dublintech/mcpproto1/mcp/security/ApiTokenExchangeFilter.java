package com.dublintech.mcpproto1.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClientException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Exchanges the caller's token for an API token before the MCP request is handled.
 *
 * <p>Tools call the API in the middle of a response the client is already receiving as a 200 event stream, so a
 * tool can't change the HTTP status. Here, before the response starts, a token that petstore-auth refuses to
 * exchange ({@code invalid_grant}, for example after petstore-auth restarted and forgot it) still gets a proper
 * {@code 401} with {@code resource_metadata}, and the MCP client logs in again. The result is cached, so the tool
 * calls that follow reuse it.
 *
 * <p>Any other failure, such as petstore-auth being unreachable, doesn't block the request: the tool's API call
 * then reports the Petstore as unavailable, and requests that don't call the API, such as {@code tools/list},
 * still work.
 */
class ApiTokenExchangeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenExchangeFilter.class);

    private final ApiTokenExchange apiTokens;

    ApiTokenExchangeFilter(ApiTokenExchange apiTokens) {
        this.apiTokens = apiTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken user) {
            try {
                apiTokens.forUser(user.getToken());
            } catch (OAuth2AuthorizationException ex) {
                if (OAuth2ErrorCodes.INVALID_GRANT.equals(ex.getError().getErrorCode())) {
                    // Handled by Spring Security like any other bad token: 401 and a fresh login.
                    throw new InvalidBearerTokenException("The token can no longer be used to reach the Petstore");
                }
                log.warn("Token exchange failed: {}", ex.getError().getErrorCode());
            } catch (RestClientException ex) {
                log.warn("Token exchange failed: {}", ex.getClass().getSimpleName());
            }
        }
        chain.doFilter(request, response);
    }
}
