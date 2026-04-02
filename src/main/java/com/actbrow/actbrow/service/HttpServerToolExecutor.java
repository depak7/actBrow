package com.actbrow.actbrow.service;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class HttpServerToolExecutor {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public HttpServerToolExecutor(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public ToolExecutionResult execute(ToolDescriptor tool, Map<String, Object> arguments) {
        try {
            String baseUrl = extractBaseUrl(tool);
            String method = extractMethod(tool);
            String path = extractPath(tool);
            Map<String, String> headers = extractHeaders(tool);
            Object body = buildBody(tool, arguments);

            String response = webClient.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(baseUrl + path)
                .headers(httpHeaders -> addHeaders(httpHeaders, headers))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(WebClientResponseException.class, ex -> {
                    return org.springframework.web.reactive.function.client.ClientResponse
                        .create(ex.getStatusCode())
                        .body(ex.getResponseBodyAsString())
                        .build()
                        .bodyToMono(String.class);
                })
                .block();

            String summary = "HTTP %s %s completed".formatted(method, path);
            return new ToolExecutionResult(true, response, summary, null);
        } catch (Exception exception) {
            return new ToolExecutionResult(false, null, "HTTP request failed", exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractBaseUrl(ToolDescriptor tool) {
        Object baseUrl = tool.metadata().get("baseUrl");
        return baseUrl != null ? baseUrl.toString() : "";
    }

    @SuppressWarnings("unchecked")
    private String extractMethod(ToolDescriptor tool) {
        Object method = tool.metadata().get("method");
        return method != null ? method.toString().toUpperCase() : "GET";
    }

    @SuppressWarnings("unchecked")
    private String extractPath(ToolDescriptor tool) {
        Object path = tool.metadata().get("path");
        return path != null ? path.toString() : "/";
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractHeaders(ToolDescriptor tool) {
        Object headers = tool.metadata().get("headers");
        if (headers instanceof Map) {
            return (Map<String, String>) headers;
        }
        return Map.of();
    }

    private Object buildBody(ToolDescriptor tool, Map<String, Object> arguments) {
        String method = extractMethod(tool);
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return null;
        }
        return arguments;
    }

    private void addHeaders(HttpHeaders httpHeaders, Map<String, String> headers) {
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(httpHeaders::set);
    }
}
