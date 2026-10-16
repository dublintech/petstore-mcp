package com.dublintech.mcpproto1.docs;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the API's own contract. The API owns the spec; consumers such as the MCP gateway read it from
 * here rather than keeping their own copy.
 */
@RestController
public class OpenApiController {

    private static final Resource SPEC = new ClassPathResource("openapi/petstore-openapi.json");

    @GetMapping(path = "/v2/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Resource openApiSpec() {
        return SPEC;
    }
}
