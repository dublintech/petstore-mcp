package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiClient;
import com.dublintech.mcpproto1.mcp.client.PetstoreApiException;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Order;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Pet;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Tag;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Task-level tools that orchestrate several Petstore API calls each, so the model makes one decision
 * instead of chaining low-level calls and remembering the business rules itself.
 */
@Component
public class PetstoreFacadeTools {

    private static final String AVAILABLE = "available";
    private static final String PENDING = "pending";

    private final PetstoreApiClient api;

    public PetstoreFacadeTools(PetstoreApiClient api) {
        this.api = api;
    }

    public record PetSummary(long id, String name, String category, List<String> tags, String status) {
    }

    public record PetSearchResult(int count, List<PetSummary> pets, String note) {
    }

    public record Purchase(long orderId, long petId, String petName, int quantity, String orderStatus,
                           String petStatus, OffsetDateTime shipDate) {
    }

    @McpTool(name = "searchPets",
            description = "Search pets by status, category and tag in one call. Returns a compact summary per pet. "
                    + "Use this to find pets to recommend or buy.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public PetSearchResult searchPets(
            @McpToolParam(description = "Category name, e.g. Dogs or Cats (case-insensitive)", required = false)
            String category,
            @McpToolParam(description = "Tag name, e.g. tag1", required = false) String tag,
            @McpToolParam(description = "available (default), pending or sold", required = false) String status) {
        String wantedStatus = isBlank(status) ? AVAILABLE : status.trim();
        List<Pet> pets = api.findPetsByStatus(List.of(wantedStatus));

        if (!isBlank(tag)) {
            // findByTags is deprecated in the spec; using it here keeps that detail out of the model's view.
            Set<Long> tagged = api.findPetsByTags(List.of(tag.trim())).stream()
                    .map(Pet::id).collect(Collectors.toSet());
            pets = pets.stream().filter(pet -> tagged.contains(pet.id())).toList();
        }
        if (!isBlank(category)) {
            // The API has no category search, so the gateway filters.
            pets = pets.stream()
                    .filter(pet -> pet.category() != null && category.trim().equalsIgnoreCase(pet.category().name()))
                    .toList();
        }

        List<PetSummary> summaries = pets.stream().map(PetstoreFacadeTools::summarise).toList();
        String note = summaries.isEmpty()
                ? "No %s pets matched%s%s. Try another status or drop a filter.".formatted(wantedStatus,
                        isBlank(category) ? "" : " category '" + category.trim() + "'",
                        isBlank(tag) ? "" : " tag '" + tag.trim() + "'")
                : null;
        return new PetSearchResult(summaries.size(), summaries, note);
    }

    /**
     * Checks availability, places the order and reserves the pet. If reserving fails, the order is cancelled
     * so no half-finished purchase is left behind.
     *
     * <p>Known limitation: the check and the order are separate API calls, so two buyers racing for the same
     * pet could both pass the check. Fixing that needs support from the API (e.g. a conditional update).
     */
    @McpTool(name = "buyPet",
            description = "Buy an available pet: places an order and marks the pet as pending. "
                    + "This is the only way to buy a pet. Confirm with the user before calling it.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false,
                    openWorldHint = false))
    public Purchase buyPet(
            @McpToolParam(description = "ID of the pet to buy") long petId,
            @McpToolParam(description = "Quantity, at least 1 (default 1)", required = false) Integer quantity) {
        int wanted = quantity == null ? 1 : quantity;
        if (wanted < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1.");
        }

        Pet pet = findPet(petId);
        if (!AVAILABLE.equals(pet.status())) {
            throw new IllegalStateException("%s (ID %d) is %s and can't be bought. Use searchPets to find available pets."
                    .formatted(pet.name(), petId, pet.status()));
        }

        Order order = api.placeOrder(petId, wanted);
        try {
            api.updatePetStatus(petId, PENDING);
        } catch (PetstoreApiException reserveFailure) {
            cancelAfterFailedReservation(order, pet, reserveFailure);
        }
        return new Purchase(order.id(), petId, pet.name(), wanted, order.status(), PENDING, order.shipDate());
    }

    private Pet findPet(long petId) {
        try {
            return api.getPet(petId);
        } catch (PetstoreApiException ex) {
            if (ex.isNotFound()) {
                throw new IllegalArgumentException(
                        "No pet with ID %d. Use searchPets to find available pets.".formatted(petId));
            }
            throw ex;
        }
    }

    /** Compensating action: undo the order so the store is left as it was. Always throws. */
    private void cancelAfterFailedReservation(Order order, Pet pet, PetstoreApiException reserveFailure) {
        try {
            api.deleteOrder(order.id());
        } catch (PetstoreApiException undoFailure) {
            throw new IllegalStateException(("Order %d was placed but %s could not be reserved (%s), and cancelling "
                    + "the order also failed (%s). The order needs manual cleanup.")
                    .formatted(order.id(), pet.name(), reserveFailure.getMessage(), undoFailure.getMessage()));
        }
        throw new IllegalStateException("Could not reserve %s (%s), so order %d was cancelled. Nothing was bought."
                .formatted(pet.name(), reserveFailure.getMessage(), order.id()));
    }

    private static PetSummary summarise(Pet pet) {
        List<String> tags = pet.tags() == null ? List.of()
                : pet.tags().stream().filter(Objects::nonNull).map(Tag::name).toList();
        return new PetSummary(pet.id(), pet.name(), pet.category() == null ? null : pet.category().name(), tags,
                pet.status());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
