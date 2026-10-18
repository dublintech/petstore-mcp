package com.dublintech.mcpproto1.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where the server remembers what each user approved for each client, so a later login skips the consent page.
 *
 * <p>The same as Spring's default, except that some scopes are never remembered
 * ({@code petstore.auth.consent.always-ask}): {@code petstore.buy}. The gateway asks for it only when the user
 * first tries to buy (step-up), and the user should see the consent page every time that happens, not just the
 * first time ever. Reading stays remembered, so an ordinary login doesn't ask again.
 */
@Configuration
public class ConsentConfig {

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            @Value("${petstore.auth.consent.always-ask}") Set<String> alwaysAsk) {
        Set<String> notRemembered = alwaysAsk.stream().map(scope -> "SCOPE_" + scope).collect(Collectors.toSet());
        OAuth2AuthorizationConsentService store = new InMemoryOAuth2AuthorizationConsentService();
        return new OAuth2AuthorizationConsentService() {
            @Override
            public void save(OAuth2AuthorizationConsent consent) {
                Set<GrantedAuthority> kept = consent.getAuthorities().stream()
                        .filter(authority -> !notRemembered.contains(authority.getAuthority()))
                        .collect(Collectors.toSet());
                if (kept.isEmpty()) {
                    store.remove(consent);
                    return;
                }
                store.save(OAuth2AuthorizationConsent.from(consent)
                        .authorities(authorities -> authorities.retainAll(kept))
                        .build());
            }

            @Override
            public void remove(OAuth2AuthorizationConsent consent) {
                store.remove(consent);
            }

            @Override
            public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
                return store.findById(registeredClientId, principalName);
            }
        };
    }
}
