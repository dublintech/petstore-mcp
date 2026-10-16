package com.dublintech.mcpproto1.pet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class PetControllerTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void getsSeededPetById() throws Exception {
        mvc.perform(get("/v2/pet/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("doggie"))
                .andExpect(jsonPath("$.category.name").value("Dogs"))
                .andExpect(jsonPath("$.status").value("available"));
    }

    @Test
    void unknownPetIs404AndNonNumericIdIs400() throws Exception {
        mvc.perform(get("/v2/pet/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.type").value("PET_NOT_FOUND"))
                .andExpect(jsonPath("$.field").doesNotExist());
        mvc.perform(get("/v2/pet/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.field").value("petId"))
                .andExpect(jsonPath("$.rule").value("TYPE"));
    }

    @Test
    void findsByStatusUsingRepeatedAndCommaSeparatedValues() throws Exception {
        mvc.perform(get("/v2/pet/findByStatus").param("status", "available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(1, 2, 5)));
        mvc.perform(get("/v2/pet/findByStatus").param("status", "pending", "sold"))
                .andExpect(jsonPath("$[*].id", contains(3, 4, 6, 7)));
        mvc.perform(get("/v2/pet/findByStatus").param("status", "pending,sold"))
                .andExpect(jsonPath("$[*].id", contains(3, 4, 6, 7)));
    }

    @Test
    void invalidStatusIs400() throws Exception {
        mvc.perform(get("/v2/pet/findByStatus").param("status", "lost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("INVALID_STATUS"))
                .andExpect(jsonPath("$.field").value("status"))
                .andExpect(jsonPath("$.rule").value("ONE_OF"))
                // The human-readable message echoes the input; that is why clients must not forward it.
                .andExpect(jsonPath("$.message").value("Invalid status value: lost"));
    }

    @Test
    void findsByTags() throws Exception {
        mvc.perform(get("/v2/pet/findByTags").param("tags", "tag3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(4, 5, 7)));
    }

    @Test
    void addsUpdatesAndDeletesPet() throws Exception {
        String created = mvc.perform(post("/v2/pet").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Biscuit","photoUrls":[],"status":"available"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(put("/v2/pet").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":%d,"name":"Biscuit II","photoUrls":[],"status":"pending"}""".formatted(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Biscuit II"));

        mvc.perform(post("/v2/pet/" + id).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "sold"))
                .andExpect(status().isOk());
        mvc.perform(get("/v2/pet/" + id))
                .andExpect(jsonPath("$.status").value("sold"));

        mvc.perform(delete("/v2/pet/" + id).header("api_key", "special-key"))
                .andExpect(status().isOk());
        mvc.perform(get("/v2/pet/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void addPetWithoutRequiredFieldsIs405() throws Exception {
        mvc.perform(post("/v2/pet").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"NoPhotos\"}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value("INVALID_PET"))
                .andExpect(jsonPath("$.field").value("photoUrls"))
                .andExpect(jsonPath("$.rule").value("REQUIRED"));
    }

    @Test
    void updateUnknownPetIs404AndMissingIdIs400() throws Exception {
        mvc.perform(put("/v2/pet").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":9999,\"name\":\"Ghost\",\"photoUrls\":[]}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/v2/pet").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ghost\",\"photoUrls\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadsImage() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "rex.png", "image/png", new byte[]{1, 2, 3});
        mvc.perform(multipart("/v2/pet/2/uploadImage").file(file).param("additionalMetadata", "profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message", containsString("rex.png, 3 bytes")))
                // Success responses keep the spec's three-field shape.
                .andExpect(jsonPath("$.field").doesNotExist())
                .andExpect(jsonPath("$.rule").doesNotExist());
    }
}
