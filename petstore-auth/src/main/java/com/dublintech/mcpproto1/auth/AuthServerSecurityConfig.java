package com.dublintech.mcpproto1.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.util.Set;

/**
 * The two security filter chains, written out instead of using Spring Boot's defaults so that one fix fits in.
 *
 * <p>The fix: when you aren't logged in, Spring Security remembers the page you asked for (the "saved request"),
 * sends you to the login page, and returns you to that page afterwards. It remembers only one page, and by default
 * it remembers any page. Browsers make background requests of their own (favicons, DevTools probes, error pages),
 * and if one of those arrives between the authorize request and the login, it replaces the authorize request. After
 * logging in you land on that stray page instead of the consent screen, for example a "Whitelabel Error Page" with
 * status 999. So only {@code /oauth2/authorize} requests are remembered here, and {@code /error} is open, so real
 * errors show their real status rather than being sent to the login page.
 *
 * Here's a sample flow:
 * 1. Request: /oauth2/authorize?.... matches FilterChain 1.
 * 2. ExceptionTranslationFilter saves request
 * 3. Response is a redirect to login
 * 4. /login happens but doesn't match FilterChain1 but matches FilterChain2.
 * 5. ...
 * 6. ...
 *
 * Some curls to have some fun...
 *
 * curl -i http://localhost:<port>/.well-known/oauth-authorization-server → 200 with JSON and no credentials. This confirms the short-circuiting.
 * curl -i -H "Accept: text/html" "http://localhost:<port>/oauth2/authorize?response_type=code&client_id=<id>" → 302 to /login.
 * The same request without the Accept: text/html header → 401. This confirms the per-request entry points.
 *
 * A spring configurer is a build time component.  It is used to create Filters and puts them in the right place
 * in a chain.
 *
 * Usually filters are independent of each other. In security the filters need to share objects and state sometimes and
 * order is really important.  This is where the Configurer abstraction comes in.
 *
 * The OAuth2AuthorizationServerConfigurer has 8 sub filters:
 *
 * OAuth2ClientAuthenticationConfigurer,
 * OAuth2AuthorizationServerMetadataEndpointConfigurer,
 * OAuth2AuthorizationEndpointConfigurer,
 * OAuth2TokenEndpointConfigurer,
 * OAuth2TokenIntrospectionEndpointConfigurer,
 * OAuth2TokenRevocationEndpointConfigurer,
 * OAuth2DeviceAuthorizationEndpointConfigurer,
 * OAuth2DeviceVerificationEndpointConfigurer.
 *
 * Those conifigurers set up corresponding filters.
 * e.g. OAuth2TokenEndpointConfigurer --> OAuth2TokenEndpointFilter
 *
 * The OAuth2TokenEndpointFilter has the token endpoint,e.g. POST /oauth2/token.
 *
 *
 *
 *
 */
@Configuration
public class AuthServerSecurityConfig {

    /** Chain 1: the OAuth endpoints themselves (authorize, token, JWKS, discovery and so on).
     *

     *
     * */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity httpSecurity) throws Exception {
        // oauth2AuthorizationServer adds all the oauth2 filters (auth endpoint, JWKS filter, metadata, introspection
        // revocation, client authentication filter
        httpSecurity.oauth2AuthorizationServer(oAuth2AuthorizationServerConfigurer -> {
                    // This chain applies only to the authorization server's own endpoints.
                    // Exampls of the auth server's own endpoints
                    // /.well-known/oauth-authorization-server
                    // /oauth2/jwks
                    // /oauth2/authorize
                    // /oauth2/token
                    // /oauth2/introspect, /oauth2/revoke  and a few more

                    // httpSecurity is a like a builder, the following line sets a requestMatcher object on it.
                    httpSecurity.securityMatcher(oAuth2AuthorizationServerConfigurer.getEndpointsMatcher());

                    // Next enable OpenID connect with default settings. An Auth Server will always have some sort of login
                    // which is pre-req so that it can ask the user what does it allow. OAuth spec doesn't say how
                    // that login works.

                    // OIDC is for the application client, not the resource server.  Now the client could figure out
                    // the user from the JWT token but it can't rely on it.  Some auth servers will put the username
                    // into the token others won't.  The JWT header and payload are base64 encoded. So anyone can reverse it.
                    // However, the client shouldn't do this, If it was to know the client info it should use OIDC and ID tokens.
                    // The client should consider the JWT token opaque.
                    // It is possible to use "opaque tokens" and the resource server has to ask the auth server what it means.
                    //
                    // Customizer.withDefault() is a lambda that does nothing.  .oidc() can take a config object to allow
                    // tweaks - not sure the line below is needed? unless flow uses openid?

                    // .oidc() will add the oidc sub configurers, filters to cover the oidc endpoints:
                    // /userinfo
                    // /.well-known/openid-configuration
                    // /connect/logout
                    // /connect/register
                    // OIDC features only switch on when the client asks for the scope openid.
                    // Think the token response will contain "id_token"
                    oAuth2AuthorizationServerConfigurer.oidc(Customizer.withDefaults());
                })
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .requestCache(cache -> cache.requestCache(authorizeRequestsOnly()))
                // Lets the OIDC userinfo endpoint accept access tokens.
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                // A browser that isn't logged in yet is sent to the login page; API callers get 401 instead.
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"), browserRequests()));
        return httpSecurity.build();
    }

    /** Chain 2: everything else, mainly the login form. */
    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity httpSecurity) throws Exception {
        // This chain has no securityMatcher, so matches everything.
        httpSecurity.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                // The same cache rule here, because this chain handles the login and reads the saved request back.
                .requestCache(cache -> cache.requestCache(authorizeRequestsOnly()))
                .formLogin(Customizer.withDefaults());
        return httpSecurity.build();
    }

    private static RequestCache authorizeRequestsOnly() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(PathPatternRequestMatcher.pathPattern("/oauth2/authorize"));
        return cache;
    }

    private static MediaTypeRequestMatcher browserRequests() {
        MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return matcher;
    }
}
