package com.dublintech.mcpproto1.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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

    @Test
    void listsFixedResourcesAndPetTemplate() {
        assertThat(client.listResources().resources()).extracting(Resource::uri).containsExactlyInAnyOrder(
                "petstore://docs/openapi", "petstore://docs/guide", "petstore://inventory");
        assertThat(client.listResourceTemplates().resourceTemplates()).extracting(ResourceTemplate::uriTemplate)
                .containsExactly("petstore://pets/{petId}");
    }

    @Test
    void readsStaticDocumentResources() {
        assertThat(read("petstore://docs/openapi")).contains("\"title\": \"Swagger Petstore\"");
        assertThat(read("petstore://docs/guide")).startsWith("# Petstore guide");
    }

    @Test
    void readsLiveInventoryResource() {
        assertThat(read("petstore://inventory")).contains("\"available\":3").contains("\"sold\":2");
    }

    @Test
    void readsPetThroughUriTemplate() {
        assertThat(read("petstore://pets/2")).contains("\"name\":\"Rex\"");
    }

    @Test
    void unknownPetResourceIsAnError() {
        assertThatThrownBy(() -> read("petstore://pets/9999")).hasMessageContaining("Pet not found");
    }

    @Test
    void listsPromptsWithArguments() {
        List<Prompt> prompts = client.listPrompts().prompts();

        assertThat(prompts).extracting(Prompt::name).containsExactlyInAnyOrder("stockReport", "findPetFor");
        Prompt findPetFor = prompts.stream().filter(p -> p.name().equals("findPetFor")).findFirst().orElseThrow();
        assertThat(findPetFor.arguments()).extracting(PromptArgument::name, PromptArgument::required)
                .containsExactlyInAnyOrder(tuple("petType", true), tuple("preferences", false));
    }

    @Test
    void stockReportEmbedsLiveResources() {
        GetPromptResult result = client.getPrompt(GetPromptRequest.builder("stockReport").build());

        assertThat(result.messages()).hasSize(3);
        EmbeddedResource inventory = (EmbeddedResource) result.messages().getFirst().content();
        assertThat(inventory.resource().uri()).isEqualTo("petstore://inventory");
        assertThat(((TextResourceContents) inventory.resource()).text()).contains("\"available\":3");
        assertThat(((TextContent) result.messages().getLast().content()).text()).contains("stock report");
    }

    @Test
    void findPetForFillsInArguments() {
        GetPromptResult result = client.getPrompt(GetPromptRequest.builder("findPetFor").arguments(
                Map.of("petType", "cat", "preferences", "tagged tag2")).build());

        assertThat(((TextContent) result.messages().getLast().content()).text())
                .contains("looking for a cat").contains("tagged tag2").contains("placeOrder");
    }

    private String read(String uri) {
        ReadResourceResult result = client.readResource(ReadResourceRequest.builder(uri).build());
        return ((TextResourceContents) result.contents().getFirst()).text();
    }

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        return client.callTool(CallToolRequest.builder(tool).arguments(arguments).build());
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
