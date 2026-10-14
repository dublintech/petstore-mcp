package com.dublintech.mcpproto1.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class McpServerTests {

    @LocalServerPort
    private int port;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build()).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        client.closeGracefully();
    }

    @Test
    void listsAllPetstoreTools() {
        List<Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrder(
                "getPetById", "findPetsByStatus", "findPetsByTags", "addPet", "updatePet", "deletePet",
                "getInventory", "placeOrder", "getOrderById", "deleteOrder",
                "getUserByName", "createUser", "deleteUser");
        Tool deletePet = tools.stream().filter(tool -> tool.name().equals("deletePet")).findFirst().orElseThrow();
        assertThat(deletePet.annotations().destructiveHint()).isTrue();
    }

    @Test
    void getsSeededPet() {
        CallToolResult result = call("getPetById", Map.of("petId", 1));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("\"name\":\"doggie\"").contains("\"status\":\"available\"");
    }

    @Test
    void findsPetsByStatus() {
        CallToolResult result = call("findPetsByStatus", Map.of("statuses", List.of("sold")));

        assertThat(text(result)).contains("Luna").contains("Thumper").doesNotContain("doggie");
    }

    @Test
    void addsPetAndPlacesOrder() {
        String pet = text(call("addPet", Map.of("name", "Biscuit", "status", "available", "tags", List.of("tag2"))));
        assertThat(pet).contains("\"name\":\"Biscuit\"");
        long petId = Long.parseLong(pet.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));

        String order = text(call("placeOrder", Map.of("petId", petId, "quantity", 1)));
        assertThat(order).contains("\"petId\":" + petId).contains("\"status\":\"placed\"");
    }

    @Test
    void reportsUnknownPetAsToolError() {
        CallToolResult result = call("getPetById", Map.of("petId", 9999));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Pet not found");
    }

    @Test
    void getsUserWithoutPassword() {
        String user = text(call("getUserByName", Map.of("username", "user1")));

        assertThat(user).contains("Alice").doesNotContain("password");
    }

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
