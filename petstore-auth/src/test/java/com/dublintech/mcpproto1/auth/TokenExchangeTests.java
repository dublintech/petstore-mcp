package com.dublintech.mcpproto1.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gateway swapping a user's token for one meant for the API (RFC 8693 token exchange, delegation).
 *
 * <p>Each test logs a user in through mcp-remote's client to get a real token for the gateway, the subject token.
 * The exchange looks it up in the server's store, so a token signed by hand would not do. The gateway then gets
 * its own token with client_credentials, the actor token, and posts both:
 * <pre>
 * POST /oauth2/token   Authorization: Basic petstore-gateway:secret
 *   grant_type=urn:ietf:params:oauth:grant-type:token-exchange
 *   subject_token=(user1's token, aud = gateway)   actor_token=(the gateway's own token)
 *   resource=http://localhost:8080
 * </pre>
 *
 * <p>Consent is remembered per user for the life of the application context, and the context is shared with
 * {@link AuthorizationCodeFlowTests}, which expects a consent page for every user. So this class discards its
 * context afterwards, and its login helper accepts a login that skips the consent page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TokenExchangeTests {

    private static final String MCP_CLIENT = "petstore-mcp-client";
    private static final String GATEWAY_CLIENT = "petstore-gateway";
    private static final String GATEWAY_SECRET = "gateway-dev-secret-not-for-production";
    private static final String GATEWAY = "http://localhost:8081/mcp";
    private static final String API = "http://localhost:8080";
    private static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void gatewayGetsAnApiTokenForTheUserNamingItselfAsActor() throws Exception {
        String userToken = userToken("user1", "petstore.read");

        MvcResult exchanged = mvc.perform(exchange(userToken, gatewayToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issued_token_type").value(ACCESS_TOKEN_TYPE))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                // Nothing to refresh: the gateway exchanges again when it needs a new one.
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn();

        Jwt jwt = jwtDecoder.decode(JsonPath.read(exchanged.getResponse().getContentAsString(), "$.access_token"));
        assertThat(jwt.getSubject()).isEqualTo("user1");
        assertThat(jwt.getAudience()).containsExactly(API);
        // No scope asked for, so the user's own scopes.
        assertThat(jwt.getClaimAsStringList("scope")).containsExactly("petstore.read");
        assertThat(jwt.getClaimAsMap(TokenExchangeRules.ACT_CLAIM)).isEqualTo(Map.of("sub", GATEWAY_CLIENT));
    }

    @Test
    void gatewayTokenOfItsOwnIsAboutTheGatewayOnly() throws Exception {
        Jwt jwt = jwtDecoder.decode(gatewayToken());
        assertThat(jwt.getSubject()).isEqualTo(GATEWAY_CLIENT);
        // Spring's default audience: the client itself. No resource server accepts it.
        assertThat(jwt.getAudience()).containsExactly(GATEWAY_CLIENT);
        assertThat(jwt.hasClaim(TokenExchangeRules.ACT_CLAIM)).isFalse();
    }

    /**
     * The check Spring leaves out: it compares requested scopes with the gateway's registration
     * (petstore.read and petstore.buy) and would grant petstore.buy here.
     */
    @Test
    void exchangeCannotAddScopesTheUserDidNotApprove() throws Exception {
        String readOnly = userToken("user2", "petstore.read");

        mvc.perform(exchange(readOnly, gatewayToken()).param("scope", "petstore.read petstore.buy"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));

        // Asking for the same or less is fine.
        mvc.perform(exchange(readOnly, gatewayToken()).param("scope", "petstore.read"))
                .andExpect(status().isOk());
    }

    @Test
    void exchangeNeedsTheClientsOwnActorToken() throws Exception {
        String userToken = userToken("user3", "petstore.read");

        // No actor token: impersonation, which would hide the gateway from the API.
        mvc.perform(exchangeWithoutActor(userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        // Someone else's token as the actor (here the user's own): the gateway can't claim to act as another party.
        mvc.perform(exchange(userToken, userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void onlyTokensMeantForTheGatewayCanBeExchanged() throws Exception {
        // An API token, from an earlier exchange: exchanging it again would build a chain of actors.
        String apiToken = JsonPath.read(mvc.perform(exchange(userToken("user1", "petstore.read"), gatewayToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.access_token");

        mvc.perform(exchange(apiToken, gatewayToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void exchangeNeedsAKnownResource() throws Exception {
        String userToken = userToken("user2", "petstore.read");
        String actor = gatewayToken();

        // Without one, the token's audience would be the gateway's client ID, which no resource server accepts.
        mvc.perform(exchangeRequest(userToken, actor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));

        mvc.perform(exchangeRequest(userToken, actor).param("resource", "https://weather.example"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));
    }

    @Test
    void onlyTheGatewayWithItsSecretCanExchange() throws Exception {
        String userToken = userToken("user3", "petstore.read");

        mvc.perform(post("/oauth2/token").with(httpBasic(GATEWAY_CLIENT, "wrong-secret"))
                        .param("grant_type", TOKEN_EXCHANGE)
                        .param("subject_token", userToken).param("subject_token_type", ACCESS_TOKEN_TYPE)
                        .param("resource", API))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));

        // mcp-remote's public client has no secret, so it can't authenticate for an exchange at all. No client is
        // recognised, so the request is turned away like any anonymous one: 401 with no OAuth error body.
        mvc.perform(post("/oauth2/token")
                        .param("grant_type", TOKEN_EXCHANGE)
                        .param("client_id", MCP_CLIENT)
                        .param("subject_token", userToken).param("subject_token_type", ACCESS_TOKEN_TYPE)
                        .param("resource", API))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder exchange(String subjectToken, String actorToken) {
        return exchangeRequest(subjectToken, actorToken).param("resource", API);
    }

    private MockHttpServletRequestBuilder exchangeRequest(String subjectToken, String actorToken) {
        return exchangeWithoutActorOrResource(subjectToken)
                .param("actor_token", actorToken)
                .param("actor_token_type", ACCESS_TOKEN_TYPE);
    }

    private MockHttpServletRequestBuilder exchangeWithoutActor(String subjectToken) {
        return exchangeWithoutActorOrResource(subjectToken).param("resource", API);
    }

    private MockHttpServletRequestBuilder exchangeWithoutActorOrResource(String subjectToken) {
        return post("/oauth2/token").with(httpBasic(GATEWAY_CLIENT, GATEWAY_SECRET))
                .param("grant_type", TOKEN_EXCHANGE)
                .param("subject_token", subjectToken)
                .param("subject_token_type", ACCESS_TOKEN_TYPE);
    }

    /** The gateway's own token: client_credentials with its secret. */
    private String gatewayToken() throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/token").with(httpBasic(GATEWAY_CLIENT, GATEWAY_SECRET))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
    }

    /**
     * A token for the gateway, as mcp-remote gets it: authorize with PKCE, approve {@code scopes} on the consent page
     * (unless this user has already approved them), redeem the code.
     */
    private String userToken(String username, String scopes) throws Exception {
        Pkce pkce = Pkce.generate();
        String authorize = "/oauth2/authorize?response_type=code&client_id=" + MCP_CLIENT
                + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A3334%2Foauth%2Fcallback"
                + "&scope=" + scopes.replace(" ", "%20") + "&state=s1"
                + "&code_challenge=" + pkce.challenge() + "&code_challenge_method=S256"
                + "&resource=http%3A%2F%2Flocalhost%3A8081%2Fmcp";
        MvcResult page = mvc.perform(get(URI.create(authorize)).with(user(username))).andReturn();
        String redirect = page.getResponse().getRedirectedUrl();
        if (page.getResponse().getStatus() == 200) {
            MockHttpServletRequestBuilder approve = post("/oauth2/authorize").with(user(username))
                    .param("client_id", MCP_CLIENT)
                    .param("state", hiddenInput(page.getResponse().getContentAsString(), "state"));
            for (String scope : scopes.split(" ")) {
                approve.param("scope", scope);
            }
            redirect = mvc.perform(approve).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        }
        assertThat(redirect).as("redirect to the callback").startsWith("http://127.0.0.1:3334/oauth/callback?code=");
        String code = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("code");

        MvcResult token = mvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", "http://127.0.0.1:3334/oauth/callback")
                        .param("client_id", MCP_CLIENT)
                        .param("code_verifier", pkce.verifier()))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = JsonPath.read(token.getResponse().getContentAsString(), "$.access_token");
        assertThat(jwtDecoder.decode(accessToken).getAudience()).isEqualTo(List.of(GATEWAY));
        return accessToken;
    }

    private static String hiddenInput(String html, String name) {
        Matcher matcher = Pattern.compile("name=\"" + name + "\" value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).as("hidden input %s", name).isTrue();
        return matcher.group(1);
    }

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
