package com.dublintech.mcpproto1.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Refresh tokens for public clients such as mcp-remote, which Spring Authorization Server doesn't support out of
 * the box.
 *
 * <p>Without a refresh token, mcp-remote would have to send the user back to the browser every time the 5-minute
 * access token expired. Spring refuses in two places, and each has a replacement here:
 * <ol>
 *   <li>{@code OAuth2RefreshTokenGenerator} returns no refresh token when a public client redeems a code.
 *       {@link Generator} issues one to any client registered for the {@code refresh_token} grant.</li>
 *   <li>The token endpoint only recognises a public client on a PKCE request (one with {@code code_verifier}).
 *       A refresh request has only {@code client_id}, so {@link Converter} and {@link Provider} recognise the client
 *       there too.</li>
 * </ol>
 *
 * <p>A public client has no secret, so whoever holds its refresh token can use it. The MCP authorization spec and
 * OAuth 2.1 allow this for public clients on condition that refresh tokens are rotated: each use returns a new one
 * and the old one stops working ({@code reuse-refresh-tokens=false} in {@code application.properties}). A stolen
 * refresh token then works at most once, and using it locks out whichever side, thief or real client, comes second.
 */
final class PublicClientRefreshTokens {

    private PublicClientRefreshTokens() {
    }

    /** Issues opaque refresh tokens: random strings, meaningful only to this server. */
    static final class Generator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

        private final StringKeyGenerator keys = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

        @Override
        public OAuth2RefreshToken generate(OAuth2TokenContext context) {
            if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())
                    || !context.getRegisteredClient().getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
                return null;
            }
            Instant issuedAt = Instant.now();
            Instant expiresAt = issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
            return new OAuth2RefreshToken(keys.generateKey(), issuedAt, expiresAt);
        }
    }

    /**
     * Reads {@code client_id} from a refresh request that carries no client credentials. Requests with credentials
     * are left to Spring's own converters.
     */
    static final class Converter implements AuthenticationConverter {

        @Override
        public Authentication convert(HttpServletRequest request) {
            if (!"POST".equals(request.getMethod())
                    || !AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))
                    || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                    || request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null
                    || request.getParameter(OAuth2ParameterNames.CLIENT_ASSERTION) != null) {
                return null;
            }
            String[] clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
            if (clientIds == null) {
                return null;
            }
            if (clientIds.length != 1) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
            }
            return new OAuth2ClientAuthenticationToken(clientIds[0], ClientAuthenticationMethod.NONE, null,
                    Map.of(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.REFRESH_TOKEN.getValue()));
        }
    }

    /**
     * Accepts the client from {@link Converter} only if it really is a public client registered for refresh tokens.
     * The refresh token itself is checked afterwards by Spring's own refresh token provider.
     */
    static final class Provider implements AuthenticationProvider {

        private final RegisteredClientRepository clients;

        Provider(RegisteredClientRepository clients) {
            this.clients = clients;
        }

        @Override
        public Authentication authenticate(Authentication authentication) {
            OAuth2ClientAuthenticationToken request = (OAuth2ClientAuthenticationToken) authentication;
            if (!ClientAuthenticationMethod.NONE.equals(request.getClientAuthenticationMethod())
                    || !AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(
                            request.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE))) {
                return null;
            }
            RegisteredClient client = clients.findByClientId((String) request.getPrincipal());
            if (client == null
                    || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                    || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
            }
            return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }
}
