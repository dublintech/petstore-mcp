package com.dublintech.mcpproto1.mcp.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(PetstoreApiProperties.class)
public class PetstoreApiConfig {

    /**
     * Every downstream call gets explicit timeouts: a tool may make several calls, and all of them
     * must finish within the MCP server's own request timeout (20s by default).
     */
    @Bean
    PetstoreApiClient petstoreApiClient(PetstoreApiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return new PetstoreApiClient(RestClient.builder().requestFactory(requestFactory), properties.baseUrl());
    }
}
