package com.dublintech.mcpproto1.mcp.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.time.Clock;

/**
 * The MCP endpoint's protections: the Origin check, then OAuth.
 *
 * <p>The MCP SDK has its own {@code DefaultServerTransportSecurityValidator}, and the WebMVC transport accepts one,
 * but Spring AI 2.0.1's auto-configuration never sets it, leaving the no-op validator in place. A servlet filter
 * adds the check without replacing Spring AI's transport bean.
 *
 * <p>For OAuth the gateway is a resource server, as the MCP authorization spec requires:
 * <ul>
 *   <li>A request without a valid token gets {@code 401} with
 *       {@code WWW-Authenticate: Bearer resource_metadata="..."}, which tells the client where to read the
 *       protected resource metadata.</li>
 *   <li>That metadata, at {@code /.well-known/oauth-protected-resource}, names the authorization server (RFC 9728).</li>
 *   <li>A token is accepted only if its signature checks out against the authorization server's published key, it
 *       hasn't expired, and its issuer and audience are right (the {@code spring.security.oauth2.resourceserver.jwt}
 *       properties).</li>
 * </ul>
 *
 *  MCP makes some parts of the OAuth spec compulsory.  For example:
 *  1. Must be /.well-know/oauth-protected-resource endpoint.  mcp-remote will hit this early.  This tells mcp-remote
 *  where to go to next ("authorization_servers":"http://localhost:9000") and the resource ("resource":"http://localhost:8081/mcp")
 *  2.
 *
 *
 */
@Configuration
@EnableConfigurationProperties(McpSecurityProperties.class)
public class McpSecurityConfig {

    static final String READ_SCOPE = "petstore.read";
    static final String BUY_SCOPE = "petstore.buy";

    @Bean
    FilterRegistrationBean<OriginValidationFilter> originValidationFilter(
            McpSecurityProperties properties,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint) {
        FilterRegistrationBean<OriginValidationFilter> registration =
                new FilterRegistrationBean<>(new OriginValidationFilter(properties.allowedOrigins()));
        registration.addUrlPatterns(mcpEndpoint, mcpEndpoint + "/*");
        // Run before anything else touches the request, Spring Security included.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** The gateway's way to an API token: see {@link ApiTokenExchange}. */
    @Bean
    ApiTokenExchange apiTokenExchange(ClientRegistrationRepository registrations,
            @Value("${petstore.api.resource}") String apiResource) {
        return new ApiTokenExchange(registrations.findByRegistrationId("petstore-api"),
                registrations.findByRegistrationId("petstore-gateway"), apiResource, Clock.systemUTC());
    }

    @Bean
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity httpSecurity, McpSecurityProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            ApiTokenExchange apiTokenExchange) throws Exception {
        httpSecurity
                // Ahead of everything that writes a 401, so each challenge gets scope="petstore.read".
                .addFilterBefore(new ReadScopeChallengeFilter(READ_SCOPE), BearerTokenAuthenticationFilter.class)
                // After the token and its scope have been checked: buyPet without petstore.buy gets the step-up
                // challenge, before anything else happens.
                .addFilterAfter(new BuyStepUpFilter(READ_SCOPE, BUY_SCOPE), AuthorizationFilter.class)
                // Its errors reach the exception translation filter, which sits earlier in the chain, so a refused
                // exchange becomes a normal 401.
                .addFilterAfter(new ApiTokenExchangeFilter(apiTokenExchange), BuyStepUpFilter.class)
                // Every request needs a token that includes petstore.read. (The metadata endpoint is answered by
                // its own filter before this rule is checked, so clients can read it without a token.)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasAuthority("SCOPE_" + READ_SCOPE))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                                builder -> builder
                                        .resource(properties.resource())
                                        .authorizationServer(issuer)
                                        // Buy first, on purpose. On a step-up, mcp-remote 0.15.0 replaces the
                                        // scope it asks for with its login scope if the request equals this list
                                        // joined in order, which "petstore.read petstore.buy" would; it would then
                                        // ask for reading only and the step-up would fail.
                                        .scope(BUY_SCOPE)
                                        .scope(READ_SCOPE)
                                        .resourceName("Petstore MCP")
                                        // Spring's default is true, which claims tokens are tied to a client TLS
                                        // certificate (RFC 8705). Ours are plain bearer tokens.
                                        .tlsClientCertificateBoundAccessTokens(false))))
                // The token travels in a header, never a cookie, so there is no CSRF risk and no need for an HTTP
                // session. (The MCP session is separate: the Mcp-Session-Id header.)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return httpSecurity.build();
    }
}
