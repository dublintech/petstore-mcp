package com.dublintech.mcpproto1.mcp.client;

import com.dublintech.mcpproto1.mcp.client.PetstoreModels.ApiResponse;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.NewOrder;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Order;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Pet;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.User;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP client for the Petstore REST API: one method per endpoint the gateway uses. Error responses and
 * connection failures both surface as {@link PetstoreApiException}, carrying the API's own message.
 */
public class PetstoreApiClient {

    private static final ParameterizedTypeReference<List<Pet>> PET_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Integer>> INVENTORY =
            new ParameterizedTypeReference<>() {};

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RestClient restClient;

    public PetstoreApiClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder
                .baseUrl(baseUrl + "/v2")
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new PetstoreApiException(response.getStatusCode().value(),
                            errorMessage(response.getBody().readAllBytes(), response.getStatusCode()));
                })
                .build();
    }

    public Pet getPet(long petId) {
        return call(() -> restClient.get().uri("/pet/{petId}", petId).retrieve().body(Pet.class));
    }

    public List<Pet> findPetsByStatus(List<String> statuses) {
        return call(() -> restClient.get()
                .uri(uri -> uri.path("/pet/findByStatus").queryParam("status", statuses.toArray()).build())
                .retrieve().body(PET_LIST));
    }

    public List<Pet> findPetsByTags(List<String> tags) {
        return call(() -> restClient.get()
                .uri(uri -> uri.path("/pet/findByTags").queryParam("tags", tags.toArray()).build())
                .retrieve().body(PET_LIST));
    }

    /** Uses the form-based update ({@code updatePetWithForm}) so only the status changes. */
    public void updatePetStatus(long petId, String status) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("status", status);
        call(() -> restClient.post().uri("/pet/{petId}", petId)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().toBodilessEntity());
    }

    public Map<String, Integer> getInventory() {
        return call(() -> restClient.get().uri("/store/inventory").retrieve().body(INVENTORY));
    }

    public Order placeOrder(long petId, int quantity) {
        return call(() -> restClient.post().uri("/store/order")
                .contentType(MediaType.APPLICATION_JSON).body(new NewOrder(petId, quantity))
                .retrieve().body(Order.class));
    }

    public Order getOrder(long orderId) {
        return call(() -> restClient.get().uri("/store/order/{orderId}", orderId).retrieve().body(Order.class));
    }

    public void deleteOrder(long orderId) {
        call(() -> restClient.delete().uri("/store/order/{orderId}", orderId).retrieve().toBodilessEntity());
    }

    public User getUser(String username) {
        return call(() -> restClient.get().uri("/user/{username}", username).retrieve().body(User.class));
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException ex) {
            throw new PetstoreApiException("The Petstore API is unavailable: " + ex.getMessage(), ex);
        }
    }

    /** Prefers the API's own {@code ApiResponse.message}; falls back to the HTTP status. */
    private String errorMessage(byte[] body, HttpStatusCode status) throws IOException {
        try {
            ApiResponse response = jsonMapper.readValue(body, ApiResponse.class);
            if (response != null && response.message() != null) {
                return response.message();
            }
        } catch (JacksonException ignored) {
            // not an ApiResponse body
        }
        return "Petstore API returned HTTP " + status.value();
    }
}
