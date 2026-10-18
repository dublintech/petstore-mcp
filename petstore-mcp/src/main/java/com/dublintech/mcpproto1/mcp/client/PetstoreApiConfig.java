package com.dublintech.mcpproto1.mcp.client;

import com.dublintech.mcpproto1.mcp.security.ApiTokenExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(PetstoreApiProperties.class)
public class PetstoreApiConfig {

    /**
     * Every downstream call gets explicit timeouts: a tool may make several calls, and all of them
     * must finish within the MCP server's own request timeout (20s by default).
     */
    @Bean
    PetstoreApiClient petstoreApiClient(PetstoreApiProperties properties, ApiTokenExchange apiTokens) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory)
                // Every API call carries the user's exchanged token. Tools run on the request's own thread, so the
                // user's token is still in the security context here.
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(apiToken(apiTokens));
                    return execution.execute(request, body);
                });
        return new PetstoreApiClient(builder, properties.baseUrl());
    }

    /**
     * An I/O failure, so PetstoreApiClient reports the Petstore as unavailable without the reason, which stays in
     * the log.
     */
    private static String apiToken(ApiTokenExchange apiTokens) throws IOException {
        try {
            return apiTokens.forCurrentUser().getTokenValue();
        } catch (RuntimeException ex) {
            throw new IOException("No API token (" + ex.getClass().getSimpleName() + ")");
        }
    }
}
