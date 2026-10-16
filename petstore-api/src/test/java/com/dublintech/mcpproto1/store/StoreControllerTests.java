package com.dublintech.mcpproto1.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class StoreControllerTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void inventoryCountsSeededPetsByStatus() throws Exception {
        mvc.perform(get("/v2/store/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(3))
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.sold").value(2));
    }

    @Test
    void getsSeededOrder() throws Exception {
        mvc.perform(get("/v2/store/order/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.petId").value(4))
                .andExpect(jsonPath("$.status").value("delivered"))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.shipDate").value("2026-09-01T10:00:00Z"));
    }

    @Test
    void invalidOrderIdIs400AndUnknownIs404() throws Exception {
        mvc.perform(get("/v2/store/order/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("INVALID_ID"))
                .andExpect(jsonPath("$.rule").value("MIN"))
                .andExpect(jsonPath("$.limit").value(1));
        mvc.perform(get("/v2/store/order/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("ORDER_NOT_FOUND"));
        mvc.perform(delete("/v2/store/order/-1")).andExpect(status().isBadRequest());
    }

    @Test
    void placesAndDeletesOrder() throws Exception {
        String created = mvc.perform(post("/v2/store/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"petId\":2,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.status").value("placed"))
                .andExpect(jsonPath("$.complete").value(false))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(delete("/v2/store/order/" + id)).andExpect(status().isOk());
        mvc.perform(get("/v2/store/order/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void orderForUnknownPetIs400() throws Exception {
        mvc.perform(post("/v2/store/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"petId\":9999,\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("INVALID_ORDER"))
                .andExpect(jsonPath("$.field").value("petId"))
                .andExpect(jsonPath("$.rule").value("MUST_EXIST"));
    }
}