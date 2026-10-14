package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.pet.Category;
import com.dublintech.mcpproto1.pet.Pet;
import com.dublintech.mcpproto1.pet.PetService;
import com.dublintech.mcpproto1.pet.PetStatus;
import com.dublintech.mcpproto1.pet.Tag;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Exposes the pet operations as MCP tools, delegating to the same {@link PetService} as the REST API.
 */
@Component
public class PetTools {

    private final PetService petService;

    public PetTools(PetService petService) {
        this.petService = petService;
    }

    @McpTool(name = "getPetById", description = "Find a pet in the store by its numeric ID.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Pet getPetById(@McpToolParam(description = "ID of the pet") long petId) {
        return petService.getPetById(petId);
    }

    @McpTool(name = "findPetsByStatus", description = "List pets that have any of the given statuses.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<Pet> findPetsByStatus(
            @McpToolParam(description = "One or more of: available, pending, sold") List<String> statuses) {
        return petService.findByStatus(statuses);
    }

    @McpTool(name = "findPetsByTags", description = "List pets tagged with any of the given tag names.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<Pet> findPetsByTags(@McpToolParam(description = "Tag names, e.g. tag1") List<String> tags) {
        return petService.findByTags(tags);
    }

    @McpTool(name = "addPet", description = "Add a new pet to the store and return it with its assigned ID.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public Pet addPet(
            @McpToolParam(description = "Name of the pet") String name,
            @McpToolParam(description = "One of: available, pending, sold", required = false) String status,
            @McpToolParam(description = "Category name, e.g. Dogs", required = false) String category,
            @McpToolParam(description = "Tag names", required = false) List<String> tags,
            @McpToolParam(description = "Photo URLs", required = false) List<String> photoUrls) {
        Pet pet = new Pet(null,
                category == null || category.isBlank() ? null : new Category(null, category),
                name,
                photoUrls == null ? List.of() : photoUrls,
                tags == null ? List.of() : tags.stream().map(tag -> new Tag(null, tag)).toList(),
                status == null || status.isBlank() ? null : PetStatus.fromValue(status));
        return petService.addPet(pet);
    }

    @McpTool(name = "updatePet", description = "Change the name and/or status of an existing pet.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public Pet updatePet(
            @McpToolParam(description = "ID of the pet") long petId,
            @McpToolParam(description = "New name", required = false) String name,
            @McpToolParam(description = "New status: available, pending or sold", required = false) String status) {
        return petService.updatePetWithForm(petId, name, status);
    }

    @McpTool(name = "deletePet", description = "Delete a pet from the store.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true,
                    openWorldHint = false))
    public String deletePet(@McpToolParam(description = "ID of the pet") long petId) {
        petService.deletePet(petId);
        return "Deleted pet " + petId;
    }
}
