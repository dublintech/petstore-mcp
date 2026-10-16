package com.dublintech.mcpproto1.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiControllerTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void servesItsOwnSpec() throws Exception {
        mvc.perform(get("/v2/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.info.title").value("Swagger Petstore"))
                .andExpect(jsonPath("$.basePath").value("/v2"))
                .andExpect(jsonPath("$.paths['/pet/{petId}'].get.operationId").value("getPetById"));
    }
}
