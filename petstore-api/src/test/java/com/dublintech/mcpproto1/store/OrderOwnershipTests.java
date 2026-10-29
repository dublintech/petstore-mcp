package com.dublintech.mcpproto1.store;

import com.dublintech.mcpproto1.security.SignedInAsGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Users see and cancel only their own orders (roadmap step 7). The sample data gives orders 1 and 2 to user1, 3 and
 * 4 to user2, and 5 to user3. Requests are made as user1 unless {@link #as(String)} names someone else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SignedInAsGateway.class)
@DirtiesContext
class OrderOwnershipTests {

    @Autowired
    private MockMvc mvc;

    /** The same token SignedInAsGateway gives every request, for another user. */
    private static RequestPostProcessor as(String user) {
        return jwt().jwt(token -> token.subject(user)).authorities(SignedInAsGateway.allScopes());
    }

    /** Other tests in this class place orders too, so this checks for the seeded orders, not an exact list. */
    @Test
    void listsOnlyTheCallersOrders() throws Exception {
        mvc.perform(get("/v2/store/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItems(1, 2)))
                .andExpect(jsonPath("$[*].id", everyItem(not(oneOf(3, 4, 5)))));
        mvc.perform(get("/v2/store/orders").with(as("user2")))
                .andExpect(jsonPath("$[*].id", hasItems(3, 4)))
                .andExpect(jsonPath("$[*].id", everyItem(not(oneOf(1, 2, 5)))));
        mvc.perform(get("/v2/store/orders").with(as("someone-new")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    /** A 403 would confirm that order 3 exists; the answer must be the same as for an order nobody has. */
    @Test
    void someoneElsesOrderLooksExactlyLikeAMissingOne() throws Exception {
        String notYours = mvc.perform(get("/v2/store/order/3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("ORDER_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get("/v2/store/order/999"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(notYours).isEqualTo(missing);
    }

    @Test
    void cannotDeleteSomeoneElsesOrder() throws Exception {
        mvc.perform(delete("/v2/store/order/3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("ORDER_NOT_FOUND"));

        mvc.perform(get("/v2/store/order/3").with(as("user2"))).andExpect(status().isOk());
    }

    @Test
    void ownerCanReadAndDeleteTheirOrder() throws Exception {
        mvc.perform(get("/v2/store/order/5").with(as("user3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.petId").value(1));
        mvc.perform(delete("/v2/store/order/5").with(as("user3"))).andExpect(status().isOk());
        mvc.perform(get("/v2/store/order/5").with(as("user3"))).andExpect(status().isNotFound());
    }

    @Test
    void newOrderBelongsToWhoeverPlacedIt() throws Exception {
        long id = placeOrder("{\"petId\":2,\"quantity\":1}", as("user2"));

        mvc.perform(get("/v2/store/order/" + id).with(as("user2"))).andExpect(status().isOk());
        mvc.perform(get("/v2/store/order/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/v2/store/orders").with(as("user2")))
                .andExpect(jsonPath("$[*].id", hasItem((int) id)));
    }

    /** The owner comes from the token only. Naming someone else in the body changes nothing. */
    @Test
    void ownerInTheBodyIsIgnored() throws Exception {
        long id = placeOrder("{\"petId\":2,\"quantity\":1,\"owner\":\"user2\"}", as("user1"));

        mvc.perform(get("/v2/store/order/" + id)).andExpect(status().isOk());
        mvc.perform(get("/v2/store/order/" + id).with(as("user2"))).andExpect(status().isNotFound());
    }

    @Test
    void ownerIsNeverInTheResponse() throws Exception {
        mvc.perform(get("/v2/store/order/1")).andExpect(jsonPath("$.owner").doesNotExist());
        mvc.perform(get("/v2/store/orders")).andExpect(jsonPath("$[0].owner").doesNotExist());
    }

    private long placeOrder(String body, RequestPostProcessor user) throws Exception {
        String created = mvc.perform(post("/v2/store/order").with(user)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }
}
