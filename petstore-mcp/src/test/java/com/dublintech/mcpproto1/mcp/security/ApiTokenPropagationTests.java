package com.dublintech.mcpproto1.mcp.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway calling the API with the user's exchanged token, end to end: a real MCP client calls a tool, the
 * gateway exchanges the token at the stand-in authorization server, and a stand-in API records what arrives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiTokenPropagationTests {

    /** The Authorization header of every request the stand-in API received, by path. */
    private static final List<String[]> API_CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer API = startApi();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void servers(DynamicPropertyRegistry registry) {
        TestAuthorizationServer.register(registry);
        registry.add("petstore.api.base-url", () -> "http://localhost:" + API.getAddress().getPort());
    }

    @AfterAll
    static void stopApi() {
        API.stop(0);
    }

    @Test
    void apiReceivesTheExchangedTokenNotTheUsersOwn() throws Exception {
        String userToken = TestAuthorizationServer.token(claims -> claims.subject("user2"));
        int exchangesBefore = TestAuthorizationServer.exchanges().size();

        try (McpSyncClient client = connect(userToken)) {
            client.callTool(CallToolRequest.builder("getInventory").arguments(Map.of()).build());
            client.callTool(CallToolRequest.builder("findPetsByStatus").arguments(Map.of("statuses", List.of("sold"))).build());
        }

        // What the gateway asked petstore-auth for: the user's token, its own token as actor, and the API.
        List<Map<String, String>> exchanges = TestAuthorizationServer.exchanges();
        assertThat(exchanges).hasSize(exchangesBefore + 1); // once per user token, then cached
        Map<String, String> exchange = exchanges.getLast();
        assertThat(exchange.get("grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:token-exchange");
        assertThat(exchange.get("subject_token")).isEqualTo(userToken);
        assertThat(exchange.get("subject_token_type")).isEqualTo("urn:ietf:params:oauth:token-type:access_token");
        assertThat(SignedJWT.parse(exchange.get("actor_token")).getJWTClaimsSet().getSubject()).isEqualTo("petstore-gateway");
        assertThat(exchange.get("resource")).isEqualTo("http://localhost:8080");
        assertThat(exchange.get("Authorization")).isEqualTo(TestAuthorizationServer.GATEWAY_BASIC_AUTH);

        // What the API received: the exchanged token on both calls, never the user's own.
        List<String> authorizations = API_CALLS.stream()
                .filter(call -> call[0].startsWith("/v2/store/inventory") || call[0].startsWith("/v2/pet/findByStatus"))
                .map(call -> call[1]).toList();
        assertThat(authorizations).hasSize(2).allSatisfy(header -> {
            assertThat(header).startsWith("Bearer ").doesNotContain(userToken);
            JWTClaimsSet claims = SignedJWT.parse(header.substring("Bearer ".length())).getJWTClaimsSet();
            assertThat(claims.getSubject()).isEqualTo("user2");
            assertThat(claims.getAudience()).containsExactly("http://localhost:8080");
            assertThat(claims.getJSONObjectClaim("act")).isEqualTo(Map.of("sub", "petstore-gateway"));
        });
    }

    /**
     * After petstore-auth forgets a token (it keeps them in memory, so after a restart), the exchange is refused.
     * The gateway answers 401 before the MCP response starts, so mcp-remote logs the user in again.
     */
    @Test
    void tokenThatCanNoLongerBeExchangedGetsA401() throws Exception {
        String forgotten = TestAuthorizationServer.token(claims -> claims.subject(TestAuthorizationServer.FORGOTTEN_USER));
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + forgotten)
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                                 "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse(""))
                .contains("error=\"invalid_token\"")
                .contains("resource_metadata=");
    }

    /** A 403 from the API (no petstore.buy) reaches the model as the gateway's own fixed message. */
    @Test
    void refusalByTheApiIsExplainedWithoutApiText() {
        try (McpSyncClient client = connect(TestAuthorizationServer.token(claims -> claims.subject("user3")))) {
            CallToolResult result = client.callTool(CallToolRequest.builder("getOrderById")
                    .arguments(Map.of("orderId", 403)).build());

            assertThat(result.isError()).isTrue();
            assertThat(((TextContent) result.content().getFirst()).text())
                    .contains("The user hasn't allowed this")
                    .doesNotContain("insufficient_scope");
        }
    }

    private McpSyncClient connect(String token) {
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .httpRequestCustomizer((request, method, uri, body, context) ->
                                request.header("Authorization", "Bearer " + token))
                        .build())
                .build();
        client.initialize();
        return client;
    }

    /** Records each request's Authorization header. Order 403 is refused as the real API refuses a missing scope. */
    private static HttpServer startApi() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().toString();
                API_CALLS.add(new String[] {path, exchange.getRequestHeaders().getFirst("Authorization")});
                int status = path.equals("/v2/store/order/403") ? 403 : 200;
                byte[] body = (status == 403 ? "" : path.startsWith("/v2/pet") ? "[]" : "{}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (status == 403) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
                }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
