package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.pet.PetService;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Exposes read-only Petstore context as MCP resources. Unlike tools, which the model decides to call,
 * resources are chosen by the user or client application and attached to the conversation.
 */
@Component
public class PetstoreResources {

    private final PetService petService;
    private final JsonMapper jsonMapper;

    public PetstoreResources(PetService petService, JsonMapper jsonMapper) {
        this.petService = petService;
        this.jsonMapper = jsonMapper;
    }

    @McpResource(uri = "petstore://docs/openapi", name = "openapi",
            title = "Petstore OpenAPI spec",
            description = "The Swagger 2.0 spec the Petstore REST API implements.",
            mimeType = "application/json")
    public String openApiSpec() {
        return classpathText("mcp/petstore-openapi.json");
    }

    @McpResource(uri = "petstore://docs/guide", name = "guide",
            title = "Petstore guide",
            description = "Pet statuses, ordering rules and sample data conventions.",
            mimeType = "text/markdown")
    public String storeGuide() {
        return classpathText("mcp/store-guide.md");
    }

    @McpResource(uri = "petstore://inventory", name = "inventory",
            title = "Current inventory",
            description = "Live count of pets per status (available, pending, sold).",
            mimeType = "application/json")
    public String inventory() {
        return jsonMapper.writeValueAsString(petService.inventory());
    }

    @McpResource(uri = "petstore://pets/{petId}", name = "pet",
            title = "Pet by ID",
            description = "A single pet as JSON, addressed by its numeric ID.",
            mimeType = "application/json")
    public String pet(String petId) {
        return jsonMapper.writeValueAsString(petService.getPetById(Long.parseLong(petId)));
    }

    private static String classpathText(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + path, ex);
        }
    }
}
