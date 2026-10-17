package com.dublintech.mcpproto1.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorization server as mcp-remote will use it: discovery, then Authorization Code + PKCE, played out
 * request by request the way a browser and the client would.
 *
 * Test some .well-known URIs RFC 8615, in the OAUTH server
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationCodeFlowTests {

    private static final String CLIENT_ID = "petstore-mcp-client";
    private static final String REDIRECT_URI = "http://127.0.0.1:3334/oauth/callback";
    private static final String GATEWAY = "http://localhost:8081/mcp";

    /**
     *   MockMvc doesn't open a port. No Tomcat and nothing on :9000. It builds a request object in memory and passes it straight through the same filter chain and servlet that real traffic would go
     *   through, then hands you back the response object. That's why the tests run in seconds and didn't need the server you had running.
     */
    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    /**
     * officially /.well-known/oauth-authorization-server is called OAuth 2.0 Authorization Server Metadata” RFC 8414.
     * Informally, people called the discovery endpoint.
     *
     * The discover endpoint is public with no login needed.
     * @throws Exception
     */
    @Test
    void discoveryAdvertisesEndpointsAndS256() throws Exception {
        // The MCP spec requires clients to refuse a server that doesn't advertise S256.
        mvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:9000"))
                .andExpect(jsonPath("$.authorization_endpoint").value("http://localhost:9000/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value("http://localhost:9000/oauth2/token"))
                .andExpect(jsonPath("$.jwks_uri").value("http://localhost:9000/oauth2/jwks"))
                .andExpect(jsonPath("$.code_challenge_methods_supported", contains("S256")))
                // "none": public clients may use the token endpoint without a client secret.
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("none")));
    }

    @Test
    void jwksPublishesOnePublicRsaKey() throws Exception {
        mvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys", hasSize(1)))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                // Only the public half: "d" is the RSA private exponent.
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void browserThatIsNotLoggedInIsSentToLogin() throws Exception {
        Pkce pkce = Pkce.generate();
        mvc.perform(get(URI.create(authorizeUrl(pkce, "s1"))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void fullFlowIssuesSignedTokenAndRejectsStolenOrReusedCodes() throws Exception {
        Pkce pkce = Pkce.generate();
        String clientState = "client-state-123";
        MockHttpSession session = new MockHttpSession();

        // 1. The browser opens the authorize URL; not logged in, so it is sent to the login page.
        mvc.perform(get(URI.create(authorizeUrl(pkce, clientState))).accept(MediaType.TEXT_HTML).session(session))
                .andExpect(redirectedUrl("/login"));

        // 2. Background requests a browser makes on its own must not replace the remembered authorize request.
        mvc.perform(get("/error").accept(MediaType.TEXT_HTML).session(session));
        mvc.perform(get("/.well-known/appspecific/com.chrome.devtools.json").session(session));

        // 3. The user logs in, and is returned to the authorize request, not to a stray page.
        MvcResult login = mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", "user1").param("password", "password1"))
                .andExpect(status().isFound())
                .andReturn();
        String backTo = login.getResponse().getRedirectedUrl();
        assertThat(backTo).contains("/oauth2/authorize?").contains("client_id=" + CLIENT_ID);
        session = (MockHttpSession) login.getRequest().getSession();

        // 4. The consent page names the client and the requested scope.
        MvcResult consentPage = mvc.perform(get(URI.create(backTo)).accept(MediaType.TEXT_HTML).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Consent required")))
                .andExpect(content().string(containsString("petstore.read")))
                .andReturn();
        String consentState = hiddenInput(consentPage.getResponse().getContentAsString(), "state");

        // 5. The user approves; the browser is sent back to the client with a code and the client's own state.
        MvcResult approved = mvc.perform(post("/oauth2/authorize").session(session)
                        .param("client_id", CLIENT_ID)
                        .param("state", consentState)
                        .param("scope", "petstore.read"))
                .andExpect(status().isFound())
                .andReturn();
        MultiValueMap<String, String> callback = UriComponentsBuilder
                .fromUriString(approved.getResponse().getRedirectedUrl()).build().getQueryParams();
        assertThat(approved.getResponse().getRedirectedUrl()).startsWith(REDIRECT_URI + "?");
        assertThat(callback.getFirst("state")).isEqualTo(clientState);
        String code = callback.getFirst("code");
        assertThat(code).isNotBlank();

        // 6a. Someone with the code but not the verifier gets nothing.
        mvc.perform(tokenRequest(code, "an-attacker-guess-that-is-long-enough-to-be-a-valid-verifier"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        // 6b. The real client, holding the verifier, gets a short-lived bearer token and a refresh token.
        MvcResult token = mvc.perform(tokenRequest(code, pkce.verifier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value("petstore.read"))
                .andExpect(jsonPath("$.expires_in").isNumber())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn();

        // The token verifies against the server's own key, as the gateway and API will check it.
        String accessToken = JsonPath.read(token.getResponse().getContentAsString(), "$.access_token");
        Jwt jwt = jwtDecoder.decode(accessToken);
        assertThat(jwt.getIssuer().toString()).isEqualTo("http://localhost:9000");
        assertThat(jwt.getSubject()).isEqualTo("user1");
        // Bound to the gateway named in the resource parameter, so no other server will accept it.
        assertThat(jwt.getAudience()).containsExactly(GATEWAY);
        assertThat(jwt.getClaimAsStringList("scope")).containsExactly("petstore.read");
        assertThat(jwt.getExpiresAt()).isBetween(jwt.getIssuedAt().plusSeconds(299), jwt.getIssuedAt().plusSeconds(300));

        // 7. When the access token expires, the client swaps the refresh token for a new pair, with no browser.
        String refreshToken = JsonPath.read(token.getResponse().getContentAsString(), "$.refresh_token");
        MvcResult refreshed = mvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn();
        Jwt renewed = jwtDecoder.decode(JsonPath.read(refreshed.getResponse().getContentAsString(), "$.access_token"));
        assertThat(renewed.getSubject()).isEqualTo("user1");
        assertThat(renewed.getAudience()).containsExactly(GATEWAY);
        assertThat(renewed.getClaimAsStringList("scope")).containsExactly("petstore.read");

        // 8. Rotation: each refresh token works once, so a stolen one is of limited use.
        String rotated = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.refresh_token");
        assertThat(rotated).isNotEqualTo(refreshToken);
        mvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        // 9. A code works only once. A second attempt suggests it was stolen, so Spring also revokes everything
        // issued from it: the current refresh token stops working too.
        mvc.perform(tokenRequest(code, pkce.verifier()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        mvc.perform(refreshRequest(rotated))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void refreshNeedsTheRegisteredPublicClient() throws Exception {
        mvc.perform(post("/oauth2/token")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", "anything")
                        .param("client_id", "no-such-client"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void tokenForAnUnknownResourceIsRefused() throws Exception {
        // The authorization server decides which servers it issues tokens for, not the client.
        Pkce pkce = Pkce.generate();
        String url = authorizeUrl(pkce, "s1").replace("localhost%3A8081%2Fmcp", "weather.example%2Fmcp");
        String code = approve("user2", url);

        mvc.perform(tokenRequest(code, pkce.verifier()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));
    }

    @Test
    void unregisteredRedirectUriIsRefusedWithoutRedirecting() throws Exception {
        // Otherwise an attacker could have the code delivered to their own site.
        Pkce pkce = Pkce.generate();
        String url = authorizeUrl(pkce, "s1").replace("127.0.0.1%3A3334", "evil.example");
        mvc.perform(get(URI.create(url)).with(user("user1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authorizeWithoutPkceIsRejected() throws Exception {
        String url = "/oauth2/authorize?response_type=code&client_id=" + CLIENT_ID
                + "&redirect_uri=" + REDIRECT_URI + "&scope=petstore.read&state=s1";
        // The redirect URI is valid, so the error goes back to the client rather than to an error page.
        mvc.perform(get(url).with(user("user1")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", allOf(startsWith(REDIRECT_URI), containsString("error=invalid_request"))));
    }

    // Already URL-encoded, so callers pass it as a URI: MockMvc's get(String) would encode it a second time.
    private static String authorizeUrl(Pkce pkce, String state) {
        return "/oauth2/authorize?response_type=code&client_id=" + CLIENT_ID
                + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A3334%2Foauth%2Fcallback"
                + "&scope=petstore.read&state=" + state
                + "&code_challenge=" + pkce.challenge() + "&code_challenge_method=S256"
                // Which server the token is for, as mcp-remote sends it (RFC 8707).
                + "&resource=http%3A%2F%2Flocalhost%3A8081%2Fmcp";
    }

    /** Logs in as {@code username}, approves the consent page and returns the code. */
    private String approve(String username, String authorizeUrl) throws Exception {
        MvcResult consentPage = mvc.perform(get(URI.create(authorizeUrl)).with(user(username)))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult approved = mvc.perform(post("/oauth2/authorize").with(user(username))
                        .param("client_id", CLIENT_ID)
                        .param("state", hiddenInput(consentPage.getResponse().getContentAsString(), "state"))
                        .param("scope", "petstore.read"))
                .andExpect(status().isFound())
                .andReturn();
        return UriComponentsBuilder.fromUriString(approved.getResponse().getRedirectedUrl()).build()
                .getQueryParams().getFirst("code");
    }

    private static org.springframework.test.web.servlet.RequestBuilder tokenRequest(String code, String verifier) {
        return post("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("client_id", CLIENT_ID)
                .param("code_verifier", verifier);
    }

    private static org.springframework.test.web.servlet.RequestBuilder refreshRequest(String refreshToken) {
        return post("/oauth2/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken)
                .param("client_id", CLIENT_ID);
    }

    private static String hiddenInput(String html, String name) {
        Matcher matcher = Pattern.compile("name=\"" + name + "\" value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).as("hidden input %s", name).isTrue();
        return matcher.group(1);
    }

    /** What mcp-remote generates at the start of each login: a random verifier and its SHA-256 challenge. */
    private record Pkce(String verifier, String challenge) {

        static Pkce generate() throws Exception {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
        }
    }
}
