package com.dublintech.mcpproto1.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class UserControllerTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void getsSeededUserWithoutExposingPassword() throws Exception {
        mvc.perform(get("/v2/user/user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void unknownUserIs404() throws Exception {
        mvc.perform(get("/v2/user/nobody")).andExpect(status().isNotFound());
    }

    @Test
    void logsInWithValidCredentials() throws Exception {
        mvc.perform(get("/v2/user/login").param("username", "user1").param("password", "password1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Rate-Limit", "5000"))
                .andExpect(header().exists("X-Expires-After"))
                .andExpect(jsonPath("$.message", startsWith("logged in user session:")));
        mvc.perform(get("/v2/user/logout")).andExpect(status().isOk());
    }

    @Test
    void loginWithWrongPasswordIs400() throws Exception {
        mvc.perform(get("/v2/user/login").param("username", "user1").param("password", "wrong"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("INVALID_CREDENTIALS"));
    }

    @Test
    void createsUpdatesAndDeletesUser() throws Exception {
        mvc.perform(post("/v2/user").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dave\",\"firstName\":\"Dave\",\"password\":\"pw\"}"))
                .andExpect(status().isOk());

        mvc.perform(put("/v2/user/dave").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dave\",\"firstName\":\"David\",\"password\":\"pw\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/v2/user/dave"))
                .andExpect(jsonPath("$.firstName").value("David"));

        mvc.perform(delete("/v2/user/dave")).andExpect(status().isOk());
        mvc.perform(get("/v2/user/dave")).andExpect(status().isNotFound());
    }

    @Test
    void createsUsersFromListAndArray() throws Exception {
        mvc.perform(post("/v2/user/createWithList").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"username\":\"erin\"},{\"username\":\"fred\"}]"))
                .andExpect(status().isOk());
        mvc.perform(post("/v2/user/createWithArray").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"username\":\"gina\"}]"))
                .andExpect(status().isOk());

        mvc.perform(get("/v2/user/erin")).andExpect(status().isOk());
        mvc.perform(get("/v2/user/fred")).andExpect(status().isOk());
        mvc.perform(get("/v2/user/gina")).andExpect(status().isOk());
    }

    @Test
    void updateUnknownUserIs404() throws Exception {
        mvc.perform(put("/v2/user/nobody").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\"}"))
                .andExpect(status().isNotFound());
    }
}