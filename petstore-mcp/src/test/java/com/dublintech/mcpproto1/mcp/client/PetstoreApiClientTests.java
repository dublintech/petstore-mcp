package com.dublintech.mcpproto1.mcp.client;

import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Order;
import com.dublintech.mcpproto1.mcp.client.PetstoreModels.Pet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Checks the gateway's side of the HTTP contract: the exact requests it sends and how it reads responses.
 */
class PetstoreApiClientTests {

    private MockRestServiceServer server;
    private PetstoreApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PetstoreApiClient(builder, "http://petstore");
    }

    @Test
    void getsPetAndIgnoresUnknownFields() {
        server.expect(requestTo("http://petstore/v2/pet/1")).andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"id":1,"name":"doggie","status":"available","category":{"id":1,"name":"Dogs"},
                         "photoUrls":[],"tags":[],"addedInV3":"ignored"}""", MediaType.APPLICATION_JSON));

        Pet pet = client.getPet(1);

        assertThat(pet.name()).isEqualTo("doggie");
        assertThat(pet.category().name()).isEqualTo("Dogs");
        server.verify();
    }

    @Test
    void sendsRepeatedStatusQueryParameters() {
        server.expect(requestTo("http://petstore/v2/pet/findByStatus?status=available&status=pending"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.findPetsByStatus(List.of("available", "pending"))).isEmpty();
        server.verify();
    }

    @Test
    void updatesStatusWithFormPost() {
        server.expect(requestTo("http://petstore/v2/pet/2")).andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("status=pending"))
                .andRespond(withSuccess("{\"code\":200,\"type\":\"unknown\",\"message\":\"2\"}",
                        MediaType.APPLICATION_JSON));

        client.updatePetStatus(2, "pending");
        server.verify();
    }

    @Test
    void placesOrderWithJsonBody() {
        server.expect(requestTo("http://petstore/v2/store/order")).andExpect(method(POST))
                .andExpect(content().json("{\"petId\":5,\"quantity\":2}"))
                .andRespond(withSuccess("{\"id\":11,\"petId\":5,\"quantity\":2,\"status\":\"placed\",\"complete\":false}",
                        MediaType.APPLICATION_JSON));

        Order order = client.placeOrder(5, 2);

        assertThat(order.id()).isEqualTo(11);
        assertThat(order.status()).isEqualTo("placed");
        server.verify();
    }

    @Test
    void deletesOrder() {
        server.expect(requestTo("http://petstore/v2/store/order/11")).andExpect(method(DELETE))
                .andRespond(withSuccess());

        client.deleteOrder(11);
        server.verify();
    }

    @Test
    void turnsErrorResponseIntoExceptionWithApiMessage() {
        server.expect(requestTo("http://petstore/v2/pet/9999"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":404,\"type\":\"error\",\"message\":\"Pet not found\"}"));

        assertThatThrownBy(() -> client.getPet(9999))
                .isInstanceOfSatisfying(PetstoreApiException.class, ex -> {
                    assertThat(ex.isNotFound()).isTrue();
                    assertThat(ex.getMessage()).isEqualTo("Pet not found");
                });
    }

    @Test
    void reportsUnreachableApiAsUnavailable() {
        PetstoreApiClient unreachable = new PetstoreApiClient(RestClient.builder(), "http://localhost:1");

        assertThatThrownBy(unreachable::getInventory)
                .isInstanceOfSatisfying(PetstoreApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(PetstoreApiException.UNAVAILABLE);
                    assertThat(ex.getMessage()).startsWith("The Petstore API is unavailable");
                });
    }
}
