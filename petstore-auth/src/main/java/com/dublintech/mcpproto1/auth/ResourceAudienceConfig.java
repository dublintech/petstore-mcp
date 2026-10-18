package com.dublintech.mcpproto1.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.List;
import java.util.Set;

/**
 * Binds each access token to the server it will be used at (RFC 8707 resource indicators).
 *
 * <p>An MCP client names that server in the {@code resource} parameter when it starts the login, for example
 * {@code resource=http://localhost:8081/mcp}. Spring Authorization Server accepts the parameter but doesn't act on it,
 * so by default a token's audience ({@code aud}) is just the client ID. Here the audience becomes the requested
 * resource, and the gateway refuses any token that doesn't name it. A token issued for some other MCP server is
 * then useless at ours, and the other way round.
 *
 * <p>Only known resources are allowed: the authorization server decides what a token may be valid for, not the
 * client.
 *
 * <p>A token exchange names its resource in the token request instead, and must name one.
 */
@Configuration
public class ResourceAudienceConfig {

    static final String RESOURCE_PARAMETER = "resource";

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> resourceAudienceCustomizer(
            @Value("${petstore.auth.resources}") Set<String> knownResources) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }
            String resource = requestedResource(context);
            if (resource == null) {
                return;
            }
            if (!knownResources.contains(resource)) {
                throw invalidTarget("Unknown resource");
            }
            context.getClaims().audience(List.of(resource));
        };
    }

    private static String requestedResource(JwtEncodingContext context) {
        // In an exchange the authorization is the user's original login, so reading it would copy the gateway's
        // resource into a token meant for the API. The exchange request carries its own.
        if (context.getAuthorizationGrant() instanceof OAuth2TokenExchangeAuthenticationToken exchange) {
            if (exchange.getResources().size() != 1) {
                throw invalidTarget(exchange.getResources().isEmpty()
                        ? "Name the resource the token is for" : "Exactly one resource is supported");
            }
            return exchange.getResources().iterator().next();
        }
        return authorizeRequestResource(context.getAuthorization());
    }

    /** The resource from the original authorize request, which the authorization keeps for the token request. */
    private static String authorizeRequestResource(OAuth2Authorization authorization) {
        if (authorization == null) {
            return null;
        }
        OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if (request == null) {
            return null;
        }
        Object value = request.getAdditionalParameters().get(RESOURCE_PARAMETER);
        // A repeated parameter arrives as an array; we issue tokens for one resource at a time.
        if (value instanceof String[] values) {
            if (values.length != 1) {
                throw invalidTarget("Exactly one resource is supported");
            }
            return values[0];
        }
        return value instanceof String resource ? resource : null;
    }

    private static OAuth2AuthenticationException invalidTarget(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_target", description,
                "https://www.rfc-editor.org/rfc/rfc8707#section-2"));
    }
}
