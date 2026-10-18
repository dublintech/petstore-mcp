package com.dublintech.mcpproto1.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The API as an OAuth resource server: every request needs a token from petstore-auth that names the API in
 * {@code aud} (the {@code spring.security.oauth2.resourceserver.jwt} properties), and each endpoint needs a scope.
 *
 * <p>The gateway gets such a token by exchanging the user's token (RFC 8693), so the token carries the user's own
 * scopes. The API therefore enforces what the user approved, independently of the gateway: if the user approved
 * only {@code petstore.read}, buying is refused here even if the gateway were tricked into trying.
 *
 * <table>
 *   <caption>Scopes per endpoint</caption>
 *   <tr><th>Requests</th><th>Scope</th></tr>
 *   <tr><td>{@code GET /v2/openapi.json}</td><td>none: the contract is public</td></tr>
 *   <tr><td>Every other {@code GET}</td><td>{@code petstore.read}</td></tr>
 *   <tr><td>The three calls {@code buyPet} makes: place an order, mark the pet pending, cancel the order</td>
 *       <td>{@code petstore.buy}</td></tr>
 *   <tr><td>Everything else (adding pets, managing users)</td><td>{@code petstore.admin}, which no client is
 *       offered yet</td></tr>
 * </table>
 */
@Configuration
public class ApiSecurityConfig {

    static final String READ = "SCOPE_petstore.read";
    static final String BUY = "SCOPE_petstore.buy";
    static final String ADMIN = "SCOPE_petstore.admin";

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/v2/openapi.json").permitAll()
                        // Error pages are rendered on a second dispatch; let them through with the original status.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v2/store/order").hasAuthority(BUY)
                        .requestMatchers(HttpMethod.DELETE, "/v2/store/order/*").hasAuthority(BUY)
                        .requestMatchers(HttpMethod.POST, "/v2/pet/*").hasAuthority(BUY)
                        .requestMatchers(HttpMethod.GET, "/v2/**").hasAuthority(READ)
                        .anyRequest().hasAuthority(ADMIN))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                // Bearer tokens in a header: no cookies, so no CSRF risk and no HTTP session.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
