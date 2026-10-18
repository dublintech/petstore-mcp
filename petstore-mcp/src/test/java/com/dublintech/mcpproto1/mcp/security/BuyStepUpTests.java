package com.dublintech.mcpproto1.mcp.security;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Step-up for buying (MCP authorization spec, scope challenge handling): a read-only token can do everything except
 * call {@code buyPet}, which gets {@code 403 insufficient_scope} naming the scopes to ask for. mcp-remote then
 * authorizes again with them and re-sends the call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BuyStepUpTests {

    private static final String BUY_CALL = """
            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"buyPet","arguments":{"petId":1}}}""";

    @DynamicPropertySource
    static void authorizationServer(DynamicPropertyRegistry registry) {
        TestAuthorizationServer.register(registry);
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private PetstoreApiClient api;

    @Test
    void buyingWithAReadOnlyTokenGetsTheScopeChallenge() throws Exception {
        HttpResponse<String> response = post(TestAuthorizationServer.validToken(), BUY_CALL);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse(""))
                .startsWith("Bearer error=\"insufficient_scope\"")
                // Everything the token should have, not just what's missing (the spec's recommendation).
                .contains("scope=\"petstore.read petstore.buy\"")
                .contains("resource_metadata=\"http://localhost:" + port + "/.well-known/oauth-protected-resource\"");
        // Refused before the tool ran: nothing was ordered.
        verify(api, never()).placeOrder(anyLong(), anyInt());
    }

    @Test
    void theChallengeAlsoCatchesBuyingInsideABatch() throws Exception {
        String batch = "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}," + BUY_CALL + "]";
        assertThat(post(TestAuthorizationServer.validToken(), batch).statusCode()).isEqualTo(403);
    }

    /** Only buying needs the extra scope: other tools work with a read-only token, and the body reaches them intact. */
    @Test
    void otherToolsNeedOnlyReading() {
        try (McpSyncClient client = connect(TestAuthorizationServer.validToken())) {
            assertThat(client.callTool(CallToolRequest.builder("findPetsByStatus")
                    .arguments(Map.of("statuses", List.of("available"))).build()).isError()).isFalse();
        }
        verify(api).findPetsByStatus(List.of("available"));
    }

    @Test
    void aTokenWithBuyingPassesTheCheck() {
        String withBuy = TestAuthorizationServer.token(claims -> claims.claim("scope", List.of("petstore.read", "petstore.buy")));
        try (McpSyncClient client = connect(withBuy)) {
            client.callTool(CallToolRequest.builder("buyPet").arguments(Map.of("petId", 1)).build());
        }
        // Reached the tool, which looked the pet up first.
        verify(api).getPet(1);
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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
}
