package com.dublintech.mcpproto1.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The consent side of step-up: mcp-remote logs in asking for {@code petstore.read}, and asks again with
 * {@code petstore.buy} when the user first buys. Reading is remembered after the first approval; buying never is,
 * so each step-up shows the consent page.
 *
 * <p>Discards its context afterwards: {@link AuthorizationCodeFlowTests} expects user1 to have no remembered consent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StepUpConsentTests {

    private static final String CLIENT_ID = "petstore-mcp-client";
    private static final String CALLBACK = "http://127.0.0.1:3334/oauth/callback";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void buyingIsAskedForEveryTimeButReadingOnlyOnce() throws Exception {
        // Login: reading only. Approved once, then remembered (unless an earlier test already approved it).
        MvcResult login = authorize("user1", "petstore.read");
        if (login.getResponse().getStatus() == 200) {
            approve("user1", login, "petstore.read");
        }
        assertThat(authorize("user1", "petstore.read").getResponse().getRedirectedUrl()).startsWith(CALLBACK);

        // Step-up: reading plus buying. The consent page appears, and approving gives a token with both.
        Pkce pkce = Pkce.generate();
        MvcResult stepUp = authorize("user1", "petstore.read petstore.buy", pkce);
        assertThat(stepUp.getResponse().getStatus()).isEqualTo(200);
        assertThat(stepUp.getResponse().getContentAsString()).contains("petstore.buy");
        String code = code(approve("user1", stepUp, "petstore.buy"));
        String token = JsonPath.read(mvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code").param("code", code)
                        .param("redirect_uri", CALLBACK).param("client_id", CLIENT_ID)
                        .param("code_verifier", pkce.verifier()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.access_token");
        assertThat(jwtDecoder.decode(token).getClaimAsStringList("scope"))
                .containsExactlyInAnyOrder("petstore.read", "petstore.buy");

        // The next step-up asks again: buying was not remembered...
        assertThat(authorize("user1", "petstore.read petstore.buy").getResponse().getStatus()).isEqualTo(200);
        // ...while reading still is.
        assertThat(authorize("user1", "petstore.read").getResponse().getRedirectedUrl()).startsWith(CALLBACK);
    }

    private MvcResult authorize(String username, String scopes) throws Exception {
        return authorize(username, scopes, Pkce.generate());
    }

    private MvcResult authorize(String username, String scopes, Pkce pkce) throws Exception {
        String url = "/oauth2/authorize?response_type=code&client_id=" + CLIENT_ID
                + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A3334%2Foauth%2Fcallback"
                + "&scope=" + scopes.replace(" ", "%20") + "&state=s1"
                + "&code_challenge=" + pkce.challenge() + "&code_challenge_method=S256"
                + "&resource=http%3A%2F%2Flocalhost%3A8081%2Fmcp";
        return mvc.perform(get(URI.create(url)).with(user(username))).andReturn();
    }

    /** Ticks {@code scopes} on the consent page and returns where the browser is sent. */
    private String approve(String username, MvcResult consentPage, String... scopes) throws Exception {
        MockHttpServletRequestBuilder approve = post("/oauth2/authorize").with(user(username))
                .param("client_id", CLIENT_ID)
                .param("state", hiddenInput(consentPage.getResponse().getContentAsString(), "state"));
        for (String scope : scopes) {
            approve.param("scope", scope);
        }
        return mvc.perform(approve).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
    }

    private static String code(String redirect) {
        assertThat(redirect).startsWith(CALLBACK + "?code=");
        return UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("code");
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
