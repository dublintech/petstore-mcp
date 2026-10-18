package com.dublintech.mcpproto1.auth;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeActor;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeCompositeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The token exchange rules (RFC 8693) that Spring Authorization Server leaves to us.
 *
 * <p>The gateway receives a user's token meant for itself ({@code aud} = the gateway) and needs one meant for the
 * API. It posts the user's token as {@code subject_token}, and its own client_credentials token as
 * {@code actor_token}, to {@code /oauth2/token}. Spring checks the gateway's secret, that the subject token is one
 * this server issued and is still active, and that the gateway's registration allows the requested scopes. It does
 * not check:
 * <ol>
 *   <li>that the <em>user</em> approved those scopes. Spring compares them with the gateway's registration only, so
 *       a token the user approved for {@code petstore.read} could come out with {@code petstore.buy};</li>
 *   <li>that the subject token was meant for the client exchanging it. Any client with the token-exchange grant
 *       could otherwise exchange a user's token it got hold of;</li>
 *   <li>that an actor token is sent at all (without one it's impersonation: the new token hides the gateway), or
 *       that it belongs to the client sending it.</li>
 * </ol>
 * These checks run while the new token is built, and refuse the exchange with a standard OAuth error. The new token
 * also gets an {@code act} claim naming the gateway, so the API sees "user1, through the gateway"; Spring doesn't
 * add that claim either.
 */
@Component
class TokenExchangeRules {

    static final String ACT_CLAIM = "act";

    /** Client ID → the audience of the tokens that client may exchange. */
    private final Map<String, String> exchangeableAudience;

    TokenExchangeRules(Environment environment) {
        this.exchangeableAudience = Binder.get(environment)
                .bind("petstore.auth.token-exchange.clients", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
    }

    void apply(JwtEncodingContext context) {
        if (!AuthorizationGrantType.TOKEN_EXCHANGE.equals(context.getAuthorizationGrantType())
                || !OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        String clientId = context.getRegisteredClient().getClientId();

        // Delegation only. Spring makes the principal a composite of user and actors when an actor token is sent.
        if (!(context.getPrincipal() instanceof OAuth2TokenExchangeCompositeAuthenticationToken delegation)) {
            throw error(OAuth2ErrorCodes.INVALID_REQUEST, "An actor_token is required");
        }
        // The first actor is the one sending this request; a client_credentials token's subject is its client ID.
        if (!clientId.equals(delegation.getActors().getFirst().getSubject())) {
            throw error(OAuth2ErrorCodes.INVALID_GRANT, "The actor_token must be the client's own token");
        }

        // For an exchange, the authorization is the user's original one, the one the subject token belongs to.
        OAuth2Authorization user = context.getAuthorization();
        OAuth2TokenExchangeAuthenticationToken request = context.getAuthorizationGrant();
        OAuth2Authorization.Token<OAuth2Token> subjectToken = user.getToken(request.getSubjectToken());
        String allowed = exchangeableAudience.get(clientId);
        if (allowed == null || subjectToken == null || !audience(subjectToken.getClaims()).contains(allowed)) {
            throw error(OAuth2ErrorCodes.INVALID_GRANT, "This client may not exchange that token");
        }

        if (!user.getAuthorizedScopes().containsAll(context.getAuthorizedScopes())) {
            throw error(OAuth2ErrorCodes.INVALID_SCOPE, "More scope than the user approved");
        }

        context.getClaims().claim(ACT_CLAIM, act(delegation.getActors()));
    }

    /**
     * {@code {"sub":"petstore-gateway"}}, with any earlier actors nested inside as RFC 8693 section 4.1 describes.
     * The rules above stop a chain from forming here, but the claim is built to the standard shape anyway.
     */
    private static Map<String, Object> act(List<OAuth2TokenExchangeActor> actors) {
        Map<String, Object> act = null;
        for (OAuth2TokenExchangeActor actor : actors.reversed()) {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("sub", actor.getSubject());
            if (act != null) {
                current.put(ACT_CLAIM, act);
            }
            act = current;
        }
        return act;
    }

    private static Collection<?> audience(Map<String, Object> claims) {
        Object aud = claims == null ? null : claims.get("aud");
        if (aud instanceof Collection<?> values) {
            return values;
        }
        return aud == null ? List.of() : List.of(aud);
    }

    private static OAuth2AuthenticationException error(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description,
                "https://www.rfc-editor.org/rfc/rfc8693#section-2.2.2"));
    }
}
