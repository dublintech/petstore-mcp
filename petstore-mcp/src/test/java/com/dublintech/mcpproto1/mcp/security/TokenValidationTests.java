package com.dublintech.mcpproto1.mcp.security;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiClient;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway as an OAuth protected resource, over real HTTP: how a client finds the authorization server, and
 * which tokens get in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenValidationTests {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
             "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""";

    @DynamicPropertySource
    static void authorizationServer(DynamicPropertyRegistry registry) {
        TestAuthorizationServer.register(registry);
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private PetstoreApiClient api;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void requestWithoutTokenIsToldWhereToFindTheMetadata() throws Exception {
        HttpResponse<String> response = initialize(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response))
                .startsWith("Bearer")
                .contains("resource_metadata=\"http://localhost:" + port + "/.well-known/oauth-protected-resource");
    }

    @Test
    void metadataIsPublicAndNamesTheAuthorizationServer() throws Exception {
        // MCP clients try the path-specific location first (RFC 9728 section 3.1), then the root one.
        for (String path : List.of("/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource")) {
            HttpResponse<String> response = get(path);

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            String json = response.body();
            assertThat((String) JsonPath.read(json, "$.resource")).isEqualTo(TestAuthorizationServer.RESOURCE);
            assertThat((List<String>) JsonPath.read(json, "$.authorization_servers"))
                    .containsExactly(TestAuthorizationServer.issuer());
            assertThat((List<String>) JsonPath.read(json, "$.scopes_supported"))
                    .containsExactly("petstore.read", "petstore.buy");
            assertThat((Boolean) JsonPath.read(json, "$.tls_client_certificate_bound_access_tokens")).isFalse();
        }
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        assertThat(initialize(TestAuthorizationServer.validToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void tokenIssuedForAnotherServerIsRefused() throws Exception {
        // Genuine signature, issuer and user, but meant for a different MCP server.
        String token = TestAuthorizationServer.token(claims -> claims.audience(List.of("https://weather.example/mcp")));
        assertRefusedAsInvalid(initialize(token));
    }

    @Test
    void tokenWithTheClientAsAudienceIsRefused() throws Exception {
        // What petstore-auth issued before it honoured the resource parameter.
        String token = TestAuthorizationServer.token(claims -> claims.audience(List.of("petstore-mcp-client")));
        assertRefusedAsInvalid(initialize(token));
    }

    @Test
    void expiredTokenIsRefused() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        String token = TestAuthorizationServer.token(claims -> claims.issuedAt(past).expiresAt(past.plusSeconds(300)));
        assertRefusedAsInvalid(initialize(token));
    }

    @Test
    void tokenFromAnotherIssuerIsRefused() throws Exception {
        String token = TestAuthorizationServer.token(claims -> claims.issuer("http://localhost:6666"));
        assertRefusedAsInvalid(initialize(token));
    }

    @Test
    void forgedTokenIsRefused() throws Exception {
        assertRefusedAsInvalid(initialize(TestAuthorizationServer.forgedToken()));
    }

    @Test
    void tokenWithoutReadScopeIsForbidden() throws Exception {
        String token = TestAuthorizationServer.token(claims -> claims.claim("scope", List.of("petstore.buy")));
        HttpResponse<String> response = initialize(token);

        // 403, not 401: the token is fine, it just doesn't allow this.
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(wwwAuthenticate(response)).contains("error=\"insufficient_scope\"");
    }

    private static void assertRefusedAsInvalid(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response)).contains("error=\"invalid_token\"");
    }

    private static String wwwAuthenticate(HttpResponse<String> response) {
        return response.headers().firstValue("WWW-Authenticate").orElse("");
    }

    private HttpResponse<String> initialize(String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
