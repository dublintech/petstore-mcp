package com.dublintech.mcpproto1.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may call the API, with real signed tokens: a stand-in for petstore-auth publishes discovery and a JWKS on a
 * random port, and the API checks signature, issuer, audience and scope exactly as in production.
 *
 * <p>The tokens look like the ones the gateway gets by token exchange: {@code sub} the user, {@code aud} the API,
 * the user's scopes, and {@code act} naming the gateway.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class ApiSecurityTests {

    private static final RSAKey KEY = key();
    private static final HttpServer ISSUER = issuer();
    private static final String API = "http://localhost:8080";

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void authorizationServer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ApiSecurityTests::issuerUrl);
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.stop(0);
    }

    @Test
    void noTokenIsRefused() throws Exception {
        mvc.perform(get("/v2/pet/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
    }

    @Test
    void theContractIsPublic() throws Exception {
        mvc.perform(get("/v2/openapi.json")).andExpect(status().isOk());
    }

    @Test
    void readScopeAllowsReading() throws Exception {
        mvc.perform(get("/v2/pet/findByStatus").param("status", "available").header("Authorization", bearer(c -> { })))
                .andExpect(status().isOk());
    }

    /** The point of exchanging rather than passing through: the user's token is for the gateway, not the API. */
    @Test
    void theUsersTokenForTheGatewayIsRefused() throws Exception {
        String gatewayToken = bearer(c -> c.audience(List.of("http://localhost:8081/mcp")));
        mvc.perform(get("/v2/pet/1").header("Authorization", gatewayToken))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
    }

    @Test
    void tokenFromAnotherIssuerIsRefused() throws Exception {
        mvc.perform(get("/v2/pet/1").header("Authorization", bearer(c -> c.issuer("http://localhost:6666"))))
                .andExpect(status().isUnauthorized());
    }

    /** A user who approved only petstore.read can't buy, whatever the gateway or the model attempts. */
    @Test
    void buyingNeedsTheBuyScope() throws Exception {
        String order = "{\"petId\":1,\"quantity\":1}";

        mvc.perform(post("/v2/store/order").contentType(MediaType.APPLICATION_JSON).content(order)
                        .header("Authorization", bearer(c -> { })))
                .andExpect(status().isForbidden())
                .andExpect(header().string("WWW-Authenticate", containsString("insufficient_scope")));

        mvc.perform(post("/v2/store/order").contentType(MediaType.APPLICATION_JSON).content(order)
                        .header("Authorization", bearer(c -> c.claim("scope", List.of("petstore.read", "petstore.buy")))))
                .andExpect(status().isOk());
    }

    @Test
    void storeAdministrationIsOfferedToNoClient() throws Exception {
        String everyScopeAClientCanGet = bearer(c -> c.claim("scope", List.of("petstore.read", "petstore.buy")));
        mvc.perform(post("/v2/pet").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nemo\",\"photoUrls\":[]}")
                        .header("Authorization", everyScopeAClientCanGet))
                .andExpect(status().isForbidden());
    }

    /** An exchanged token for user1 with petstore.read, with {@code changes} applied. */
    private static String bearer(Consumer<JwtClaimsSet.Builder> changes) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuerUrl())
                .subject("user1")
                .audience(List.of(API))
                .claim("scope", List.of("petstore.read"))
                .claim("act", Map.of("sub", "petstore-gateway"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        changes.accept(claims);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY.getKeyID()).build();
        return "Bearer " + new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)))
                .encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static String issuerUrl() {
        return "http://localhost:" + ISSUER.getAddress().getPort();
    }

    private static HttpServer issuer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String url = "http://localhost:" + server.getAddress().getPort();
            server.createContext("/.well-known/", exchange -> respond(exchange,
                    "{\"issuer\":\"%s\",\"jwks_uri\":\"%s/jwks\"}".formatted(url, url)));
            server.createContext("/jwks", exchange -> respond(exchange, new JWKSet(KEY).toPublicJWKSet().toString()));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("api-test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
