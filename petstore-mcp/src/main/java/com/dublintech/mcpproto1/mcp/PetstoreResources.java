package com.dublintech.mcpproto1.mcp;

import com.dublintech.mcpproto1.mcp.client.PetstoreApiClient;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Pet;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Tag;
import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Exposes read-only Petstore context as MCP resources. Unlike tools, which the model decides to call,
 * resources are chosen by the user or client application and attached to the conversation. Each one here
 * shows a different kind of resource: a fixed document, live data, derived data, a URI template and binary content.
 */
@Component
public class PetstoreResources {

    private static final List<String> ALL_STATUSES = List.of("available", "pending", "sold");

    private final PetstoreApiClient api;
    private final JsonMapper jsonMapper;

    public PetstoreResources(PetstoreApiClient api, JsonMapper jsonMapper) {
        this.api = api;
        this.jsonMapper = jsonMapper;
    }

    public record CategorySummary(String name, long pets, long available) {
    }

    /** Fixed document: rules the model should know before it acts. */
    @McpResource(uri = "petstore://docs/guide", name = "guide",
            title = "Petstore guide",
            description = "Pet statuses, how buying works, and sample data conventions.",
            mimeType = "text/markdown")
    public String storeGuide() {
        try {
            return new ClassPathResource("mcp/store-guide.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the store guide", ex);
        }
    }

    /** Live data: read from the API on every request. */
    @McpResource(uri = "petstore://inventory", name = "inventory",
            title = "Current inventory",
            description = "Live count of pets per status (available, pending, sold).",
            mimeType = "application/json")
    public String inventory() {
        return jsonMapper.writeValueAsString(GatewayErrors.call(api::getInventory));
    }

    /** Derived data: the API has no categories endpoint, so the gateway computes the list from all pets. */
    @McpResource(uri = "petstore://categories", name = "categories",
            title = "Pet categories",
            description = "Every pet category with its pet count and how many are available. "
                    + "These are the valid values for searchPets' category filter.",
            mimeType = "application/json")
    public String categories() {
        Map<String, List<Pet>> byCategory = GatewayErrors.call(() -> api.findPetsByStatus(ALL_STATUSES)).stream()
                .filter(pet -> pet.category() != null && pet.category().name() != null)
                .collect(Collectors.groupingBy(pet -> pet.category().name()));
        List<CategorySummary> summaries = byCategory.entrySet().stream()
                .map(entry -> new CategorySummary(entry.getKey(), entry.getValue().size(),
                        entry.getValue().stream().filter(pet -> "available".equals(pet.status())).count()))
                .sorted(Comparator.comparing(CategorySummary::name))
                .toList();
        return jsonMapper.writeValueAsString(summaries);
    }

    /** URI template: one resource per pet, addressed by ID. */
    @McpResource(uri = "petstore://pets/{petId}", name = "pet",
            title = "Pet by ID",
            description = "A single pet as JSON, addressed by its numeric ID.",
            mimeType = "application/json")
    public String pet(String petId) {
        return jsonMapper.writeValueAsString(GatewayErrors.call(() -> api.getPet(Long.parseLong(petId))));
    }

    /**
     * Binary content: a PNG card for the pet, sent base64-encoded as a blob. The card is drawn here rather than
     * downloaded from the pet's photoUrls, because fetching URLs taken from data invites server-side request forgery.
     */
    @McpResource(uri = "petstore://pets/{petId}/card", name = "petCard",
            title = "Pet card image",
            description = "A PNG image card showing the pet's name, category, status and tags.",
            mimeType = "image/png")
    public ReadResourceResult petCard(String petId) {
        Pet pet = GatewayErrors.call(() -> api.getPet(Long.parseLong(petId)));
        String png = Base64.getEncoder().encodeToString(renderCard(pet));
        BlobResourceContents contents = BlobResourceContents.builder("petstore://pets/" + petId + "/card", png)
                .mimeType("image/png").build();
        return ReadResourceResult.builder(List.of(contents)).build();
    }

    private static byte[] renderCard(Pet pet) {
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(statusColour(pet.status()));
            g.fillRect(0, 0, 400, 200);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
            g.drawString(Objects.toString(pet.name(), "Unnamed"), 24, 64);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            g.drawString(pet.category() == null ? "No category" : pet.category().name(), 24, 104);
            g.drawString("Status: " + pet.status(), 24, 136);
            String tags = pet.tags() == null ? "" : pet.tags().stream().filter(Objects::nonNull).map(Tag::name)
                    .collect(Collectors.joining(", "));
            g.drawString(tags.isEmpty() ? "No tags" : "Tags: " + tags, 24, 168);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not render pet card", ex);
        }
    }

    private static Color statusColour(String status) {
        return switch (Objects.toString(status, "")) {
            case "available" -> new Color(0x2E7D32);
            case "pending" -> new Color(0xEF6C00);
            case "sold" -> new Color(0x616161);
            default -> new Color(0x37474F);
        };
    }
}
