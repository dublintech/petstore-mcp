package com.dublintech.mcpproto1.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * For tests about what the API does, not who may call it: every MockMvc request is made as if the gateway had
 * exchanged a token for user1 with every scope. The token is placed straight into the security context, so no
 * signature is checked. {@link ApiSecurityTests} sends real signed tokens instead.
 */
@TestConfiguration
public class SignedInAsGateway {

    @Bean
    MockMvcBuilderCustomizer everyRequestHasAToken() {
        return builder -> builder.defaultRequest(get("/").with(jwt()
                .jwt(token -> token.subject("user1").claim("act", Map.of("sub", "petstore-gateway")))
                .authorities(allScopes())));
    }

    /** Every scope the API checks, as Spring's authorities. */
    public static GrantedAuthority[] allScopes() {
        return new GrantedAuthority[] {new SimpleGrantedAuthority(ApiSecurityConfig.READ),
                new SimpleGrantedAuthority(ApiSecurityConfig.BUY), new SimpleGrantedAuthority(ApiSecurityConfig.ADMIN)};
    }
}
