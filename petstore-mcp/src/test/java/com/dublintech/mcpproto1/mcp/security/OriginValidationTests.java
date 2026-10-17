package com.dublintech.mcpproto1.mcp.security;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiClient;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DNS rebinding protection on the real MCP endpoint: a browser page from an untrusted origin must be refused,
 * while non-browser clients (no Origin header) and allowed origins get through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OriginValidationTests {

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
    void refusesUntrustedOriginWithJsonRpcErrorAndNoId() throws Exception {
        HttpResponse<String> response = post("http://evil.example");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Forbidden: invalid Origin\"}}");
        assertThat(response.headers().firstValue("Mcp-Session-Id")).isEmpty();
    }

    @Test
    void refusesRebindingStyleOriginOnLocalhostLookalike() throws Exception {
        // A rebinding page's origin is its own domain, even though its DNS now points at 127.0.0.1.
        assertThat(post("http://attacker.example:8081").statusCode()).isEqualTo(403);
        // Same host, different port, is a different origin.
        assertThat(post("http://localhost:9999").statusCode()).isEqualTo(403);
    }

    @Test
    void refusesOpaqueNullOrigin() throws Exception {
        // Sandboxed iframes and file:// pages send the literal "null".
        assertThat(post("null").statusCode()).isEqualTo(403);
    }

    @Test
    void allowsClientsThatSendNoOrigin() throws Exception {
        // The Claude Desktop route: mcp-remote makes its requests from Node.js, with no Origin header.
        assertThat(post(null).statusCode()).isEqualTo(200);
    }

    @Test
    void allowsTheMcpInspectorByDefaultUnderEitherHostName() throws Exception {
        assertThat(post("http://localhost:6274").statusCode()).isEqualTo(200);
        assertThat(post("http://127.0.0.1:6274").statusCode()).isEqualTo(200);
        assertThat(post("HTTP://LOCALHOST:6274").statusCode()).isEqualTo(200);
    }

    @Test
    void appliesToGetAndDeleteToo() throws Exception {
        for (String method : new String[]{"GET", "DELETE"}) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Origin", "http://evil.example")
                    .header("Accept", "text/event-stream")
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .as(method).isEqualTo(403);
        }
    }

    @Test
    void normalisesEquivalentSpellings() {
        assertThat(OriginValidationFilter.normalise("http://LocalHost:6274/")).isEqualTo("http://localhost:6274");
        assertThat(OriginValidationFilter.normalise("https://example.com")).isEqualTo("https://example.com:443");
        assertThat(OriginValidationFilter.normalise("null")).isNull();
        assertThat(OriginValidationFilter.normalise("file:///tmp/x.html")).isNull();
    }

    @Test
    void checksOriginBeforeTheToken() throws Exception {
        // A bad origin is refused even with no token: the Origin filter runs before Spring Security.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Origin", "http://evil.example")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    // Sends a valid token, so these tests isolate the Origin check.
    private HttpResponse<String> post(String origin) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + TestAuthorizationServer.validToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE));
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
