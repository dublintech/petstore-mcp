package com.dublintech.mcpproto1.mcp.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientTokenExchangeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.TokenExchangeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gets the token the gateway sends to the Petstore API: the caller's token exchanged at petstore-auth for one
 * meant for the API (RFC 8693, delegation).
 *
 * <pre>
 * POST {token-uri}   Authorization: Basic petstore-gateway:secret
 *   grant_type=urn:ietf:params:oauth:grant-type:token-exchange
 *   subject_token=(the user's token, aud = the gateway)      subject_token_type=...:access_token
 *   actor_token=(the gateway's client_credentials token)    actor_token_type=...:access_token
 *   resource=http://localhost:8080
 * </pre>
 *
 * <p>The answer has the user as {@code sub}, the API as {@code aud}, the user's own scopes, and the gateway in
 * {@code act}. Passing the user's token through instead is forbidden by the MCP spec, and the API refuses it.
 *
 * <p>Results are cached by the user's token, not by the user: when the user logs in again with different
 * scopes they get a new token, and so a new exchange with those scopes. A cached token is used until shortly before
 * it expires. The gateway's own token is cached the same way.
 */
public class ApiTokenExchange {

    /** Renew this long before expiry, so a token doesn't run out between being handed over and arriving. */
    private static final Duration EARLY = Duration.ofSeconds(30);

    private final ClientRegistration exchange;
    private final ClientRegistration self;
    private final RestClientTokenExchangeTokenResponseClient exchangeClient = new RestClientTokenExchangeTokenResponseClient();
    private final RestClientClientCredentialsTokenResponseClient selfClient = new RestClientClientCredentialsTokenResponseClient();
    private final Clock clock;
    private final Map<String, OAuth2AccessToken> byUserToken = new ConcurrentHashMap<>();
    private volatile OAuth2AccessToken actorToken;

    public ApiTokenExchange(ClientRegistration exchange, ClientRegistration self, String apiResource, Clock clock) {
        this.exchange = exchange;
        this.self = self;
        this.clock = clock;
        exchangeClient.setParametersCustomizer(parameters -> parameters.set("resource", apiResource));
    }

    /** The API token for the user behind the current request. */
    public OAuth2AccessToken forCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken user)) {
            throw new IllegalStateException("No user token to exchange");
        }
        return forUser(user.getToken());
    }

    /**
     * The API token for {@code userToken}, exchanging it if needed.
     *
     * @throws org.springframework.security.oauth2.core.OAuth2AuthorizationException if petstore-auth refuses
     *         (for example {@code invalid_grant} once it no longer knows the user's token) or can't be reached
     */
    public OAuth2AccessToken forUser(Jwt userToken) {
        OAuth2AccessToken cached = byUserToken.get(userToken.getTokenValue());
        if (usable(cached)) {
            return cached;
        }
        byUserToken.values().removeIf(token -> !usable(token));
        // A Jwt would be sent as token type "jwt"; as an access token it is sent as "access_token".
        OAuth2AccessToken subject = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                userToken.getTokenValue(), userToken.getIssuedAt(), userToken.getExpiresAt());
        OAuth2AccessToken apiToken = exchangeClient
                .getTokenResponse(new TokenExchangeGrantRequest(exchange, subject, actorToken()))
                .getAccessToken();
        byUserToken.put(userToken.getTokenValue(), apiToken);
        return apiToken;
    }

    private OAuth2AccessToken actorToken() {
        OAuth2AccessToken current = actorToken;
        if (!usable(current)) {
            synchronized (this) {
                current = actorToken;
                if (!usable(current)) {
                    current = selfClient.getTokenResponse(new OAuth2ClientCredentialsGrantRequest(self)).getAccessToken();
                    actorToken = current;
                }
            }
        }
        return current;
    }

    private boolean usable(OAuth2AccessToken token) {
        Instant expiresAt = token == null ? null : token.getExpiresAt();
        return expiresAt != null && clock.instant().isBefore(expiresAt.minus(EARLY));
    }
}
