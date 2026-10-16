package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiClient;
import com.dublintech.mcpproto1.mcp.client.PetstoreApiException;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Category;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Order;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Pet;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Tag;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the real MCP gateway and talks to it with a real MCP client. The Petstore API itself is replaced by a
 * mock, so these tests need no running API and can simulate downstream failures.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpGatewayTests {

    private static final Pet DOGGIE = pet(1, "doggie", "Dogs", "available", "tag1");
    private static final Pet REX = pet(2, "Rex", "Dogs", "available", "tag1", "tag2");
    private static final Pet TWEETY = pet(5, "Tweety", "Birds", "available", "tag3");
    private static final Pet WHISKERS = pet(3, "Whiskers", "Cats", "pending", "tag2");

    @LocalServerPort
    private int port;

    @MockitoBean
    private PetstoreApiClient api;

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
    void exposesReadOnlyToolsPlusFacadeTools() {
        List<Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrder(
                "getPetById", "findPetsByStatus", "findPetsByTags", "getInventory", "getOrderById", "getUserByName",
                "searchPets", "buyPet");
        assertThat(tools).filteredOn(tool -> !tool.name().equals("buyPet"))
                .allSatisfy(tool -> assertThat(tool.annotations().readOnlyHint()).isTrue());
    }

    @Test
    void passesApiErrorMessageThroughAsToolError() {
        when(api.getPet(9999)).thenThrow(new PetstoreApiException(404, "Pet not found"));

        CallToolResult result = call("getPetById", Map.of("petId", 9999));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Pet not found");
    }

    // --- searchPets ---

    @Test
    void searchPetsCombinesStatusTagAndCategory() {
        when(api.findPetsByStatus(List.of("available"))).thenReturn(List.of(DOGGIE, REX, TWEETY));
        when(api.findPetsByTags(List.of("tag1"))).thenReturn(List.of(DOGGIE, REX));

        String result = text(call("searchPets", Map.of("category", "dogs", "tag", "tag1")));

        assertThat(result).contains("\"count\":2").contains("doggie").contains("Rex").doesNotContain("Tweety")
                .doesNotContain("photoUrls");
    }

    @Test
    void searchPetsExplainsAnEmptyResult() {
        when(api.findPetsByStatus(List.of("available"))).thenReturn(List.of(DOGGIE, REX, TWEETY));

        String result = text(call("searchPets", Map.of("category", "Cats")));

        assertThat(result).contains("\"count\":0").contains("No available pets matched category 'Cats'");
    }

    // --- buyPet ---

    @Test
    void buyPetPlacesOrderAndReservesPet() {
        when(api.getPet(1)).thenReturn(DOGGIE);
        when(api.placeOrder(1, 1)).thenReturn(new Order(11L, 1L, 1, null, "placed", false));

        CallToolResult result = call("buyPet", Map.of("petId", 1));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("\"orderId\":11").contains("\"petName\":\"doggie\"")
                .contains("\"petStatus\":\"pending\"");
        verify(api).updatePetStatus(1, "pending");
    }

    @Test
    void buyPetRefusesPetThatIsNotAvailable() {
        when(api.getPet(3)).thenReturn(WHISKERS);

        CallToolResult result = call("buyPet", Map.of("petId", 3));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Whiskers (ID 3) is pending and can't be bought");
        verify(api, never()).placeOrder(anyLong(), anyInt());
    }

    @Test
    void buyPetPointsUnknownPetToSearch() {
        when(api.getPet(42)).thenThrow(new PetstoreApiException(404, "Pet not found"));

        CallToolResult result = call("buyPet", Map.of("petId", 42));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("No pet with ID 42. Use searchPets");
    }

    @Test
    void buyPetCancelsOrderWhenReservationFails() {
        when(api.getPet(1)).thenReturn(DOGGIE);
        when(api.placeOrder(1, 1)).thenReturn(new Order(11L, 1L, 1, null, "placed", false));
        doThrow(new PetstoreApiException(503, "The Petstore API is unavailable"))
                .when(api).updatePetStatus(1, "pending");

        CallToolResult result = call("buyPet", Map.of("petId", 1));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("order 11 was cancelled. Nothing was bought.");
        verify(api).deleteOrder(11);
    }

    @Test
    void buyPetReportsWhenCancellationAlsoFails() {
        when(api.getPet(1)).thenReturn(DOGGIE);
        when(api.placeOrder(1, 1)).thenReturn(new Order(11L, 1L, 1, null, "placed", false));
        doThrow(new PetstoreApiException(503, "down")).when(api).updatePetStatus(1, "pending");
        doThrow(new PetstoreApiException(503, "still down")).when(api).deleteOrder(11);

        CallToolResult result = call("buyPet", Map.of("petId", 1));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("needs manual cleanup");
    }

    // --- resources and prompts ---

    @Test
    void servesResourcesFromTheApi() {
        when(api.getInventory()).thenReturn(Map.of("available", 3, "pending", 2, "sold", 2));
        when(api.getPet(2)).thenReturn(REX);

        assertThat(client.listResources().resources()).extracting(Resource::uri).containsExactlyInAnyOrder(
                "petstore://docs/openapi", "petstore://docs/guide", "petstore://inventory");
        assertThat(client.listResourceTemplates().resourceTemplates()).extracting(ResourceTemplate::uriTemplate)
                .containsExactly("petstore://pets/{petId}");
        assertThat(read("petstore://inventory")).contains("\"available\":3");
        assertThat(read("petstore://pets/2")).contains("\"name\":\"Rex\"");
        assertThat(read("petstore://docs/openapi")).contains("\"title\": \"Swagger Petstore\"");
    }

    @Test
    void unknownPetResourceIsAnError() {
        when(api.getPet(9999)).thenThrow(new PetstoreApiException(404, "Pet not found"));

        assertThatThrownBy(() -> read("petstore://pets/9999")).hasMessageContaining("Pet not found");
    }

    @Test
    void promptsEmbedLiveDataAndPointAtFacadeTools() {
        when(api.getInventory()).thenReturn(Map.of("available", 3, "pending", 2, "sold", 2));

        assertThat(client.listPrompts().prompts()).extracting(Prompt::name)
                .containsExactlyInAnyOrder("stockReport", "findPetFor");

        GetPromptResult report = client.getPrompt(GetPromptRequest.builder("stockReport").build());
        EmbeddedResource inventory = (EmbeddedResource) report.messages().getFirst().content();
        assertThat(((TextResourceContents) inventory.resource()).text()).contains("\"available\":3");

        GetPromptResult findPet = client.getPrompt(GetPromptRequest.builder("findPetFor")
                .arguments(Map.of("petType", "cat")).build());
        assertThat(((TextContent) findPet.messages().getLast().content()).text())
                .contains("searchPets").contains("buyPet");
    }

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        return client.callTool(CallToolRequest.builder(tool).arguments(arguments).build());
    }

    private String read(String uri) {
        return ((TextResourceContents) client.readResource(ReadResourceRequest.builder(uri).build())
                .contents().getFirst()).text();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static Pet pet(long id, String name, String category, String status, String... tags) {
        return new Pet(id, new Category(id, category), name, List.of(),
                java.util.Arrays.stream(tags).map(tag -> new Tag(null, tag)).toList(), status);
    }
}
