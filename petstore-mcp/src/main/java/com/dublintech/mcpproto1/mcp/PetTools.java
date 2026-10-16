package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiClient;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Pet;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Read-only pet tools that map one-to-one onto Petstore API calls. Changes go through
 * {@link PetstoreFacadeTools} instead.
 */
@Component
public class PetTools {

    private final PetstoreApiClient api;

    public PetTools(PetstoreApiClient api) {
        this.api = api;
    }

    @McpTool(name = "getPetById", description = "Find a pet in the store by its numeric ID.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Pet getPetById(@McpToolParam(description = "ID of the pet") long petId) {
        return api.getPet(petId);
    }

    @McpTool(name = "findPetsByStatus", description = "List pets that have any of the given statuses.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<Pet> findPetsByStatus(
            @McpToolParam(description = "One or more of: available, pending, sold") List<String> statuses) {
        return api.findPetsByStatus(statuses);
    }

    @McpTool(name = "findPetsByTags", description = "List pets tagged with any of the given tag names.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<Pet> findPetsByTags(@McpToolParam(description = "Tag names, e.g. tag1") List<String> tags) {
        return api.findPetsByTags(tags);
    }
}
