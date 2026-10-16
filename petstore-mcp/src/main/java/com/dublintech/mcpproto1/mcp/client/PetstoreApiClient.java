package com.dublintech.mcpproto1.mcp.client;

import com.dublintech.mcpproto1.mcp.client.PetstoreModels.ApiResponse;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.NewOrder;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Order;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Pet;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP client for the Petstore REST API: one method per endpoint the gateway uses. Every failure (error status,
 * unreachable API, unreadable response) surfaces as a {@link PetstoreApiException} holding only checked values.
 */
public class PetstoreApiClient {

    private static final Logger log = LoggerFactory.getLogger(PetstoreApiClient.class);

    private static final ParameterizedTypeReference<List<Pet>> PET_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Integer>> INVENTORY =
            new ParameterizedTypeReference<>() {};

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RestClient restClient;

    public PetstoreApiClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder
                .baseUrl(baseUrl + "/v2")
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw toException(request, response.getStatusCode(), response.getBody().readAllBytes());
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

    /** Turns every client-side failure into a {@link PetstoreApiException}; the details go to the log only. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException ex) {
            PetstoreApiException failure = PetstoreApiException.unavailable();
            log.warn("Petstore API unreachable [ref {}]: {}", failure.getReference(), forLog(ex.getMessage()));
            throw failure;
        } catch (RestClientException ex) {
            PetstoreApiException failure = PetstoreApiException.badResponse();
            log.warn("Unreadable Petstore API response [ref {}]: {}", failure.getReference(), forLog(ex.getMessage()));
            throw failure;
        }
    }

    /**
     * Keeps the code and typed detail from an error response. The free-text message is logged for operators and
     * then discarded: it may echo stored or user-supplied text, so it must never reach the model.
     */
    private PetstoreApiException toException(HttpRequest request, HttpStatusCode status, byte[] body) {
        ApiResponse response = parse(body);
        PetstoreApiException failure = response == null
                ? new PetstoreApiException(status.value(), null, null, null, null)
                : new PetstoreApiException(status.value(), response.type(), response.field(), response.rule(),
                        response.limit());
        log.warn("Petstore API error [ref {}]: {} {} -> {} code={} message={}", failure.getReference(),
                request.getMethod(), request.getURI(), status.value(),
                response == null ? null : forLog(response.type()), response == null ? null : forLog(response.message()));
        return failure;
    }

    private ApiResponse parse(byte[] body) {
        try {
            return jsonMapper.readValue(body, ApiResponse.class);
        } catch (JacksonException ex) {
            return null;
        }
    }

    /** Stops logged text from forging extra log lines, and caps its length. */
    private static String forLog(String text) {
        if (text == null) {
            return null;
        }
        String oneLine = text.replaceAll("[\\r\\n\\t]", " ");
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }
}
