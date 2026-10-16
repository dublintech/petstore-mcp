package com.dublintech.mcpproto1.mcp;

import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Exposes reusable, user-selected starting points as MCP prompts. A prompt returns ready-made messages
 * that the client drops into the conversation; they can embed resources and steer the model towards tools.
 */
@Component
public class PetstorePrompts {

    private final PetstoreResources resources;

    public PetstorePrompts(PetstoreResources resources) {
        this.resources = resources;
    }

    @McpPrompt(name = "stockReport", title = "Stock report",
            description = "Summarise current pet stock against the store rules, with the inventory and guide attached.")
    public GetPromptResult stockReport() {
        return GetPromptResult.builder(List.of(
                        embed("petstore://inventory", "application/json", resources.inventory()),
                        embed("petstore://docs/guide", "text/markdown", resources.storeGuide()),
                        user("""
                                Using the attached inventory and store guide, write a short stock report:
                                1. How many pets are available, pending and sold.
                                2. Anything that needs attention, such as pending orders that may need approving.
                                3. One or two concrete suggestions. If you want detail on specific pets, use the
                                   searchPets tool rather than guessing.""")))
                .description("Petstore stock report")
                .build();
    }

    @McpPrompt(name = "findPetFor", title = "Find me a pet",
            description = "Help a customer choose an available pet and, if they agree, order it.")
    public GetPromptResult findPetFor(
            @McpArg(name = "petType", description = "Kind of pet wanted, e.g. dog, cat, bird", required = true)
            String petType,
            @McpArg(name = "preferences", description = "Anything else that matters, e.g. 'tagged tag1'")
            String preferences) {
        String extra = preferences == null || preferences.isBlank() ? "" : "\nTheir preferences: " + preferences + ".";
        return GetPromptResult.builder(List.of(
                        embed("petstore://docs/guide", "text/markdown", resources.storeGuide()),
                        user("""
                                A customer is looking for a %s.%s
                                1. Use searchPets, filtering by category where it helps, to find available matches.
                                2. Recommend the best one or two, explaining why.
                                3. Ask the customer to confirm before using buyPet; never buy without a yes."""
                                .formatted(petType, extra))))
                .description("Find a " + petType)
                .build();
    }

    private static PromptMessage user(String text) {
        return PromptMessage.builder(Role.USER, TextContent.builder(text).build()).build();
    }

    /** Embeds a resource's contents in the prompt, so the client doesn't need to fetch it separately. */
    private static PromptMessage embed(String uri, String mimeType, String text) {
        TextResourceContents contents = TextResourceContents.builder(uri, text).mimeType(mimeType).build();
        return PromptMessage.builder(Role.USER, EmbeddedResource.builder(contents).build()).build();
    }
}
