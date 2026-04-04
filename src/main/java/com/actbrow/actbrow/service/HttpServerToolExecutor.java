package com.actbrow.actbrow.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;

@Component
public class HttpServerToolExecutor {

	private final RestTemplate restTemplate;

	public HttpServerToolExecutor() {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(Duration.ofSeconds(30));
		this.restTemplate = new RestTemplate(factory);
	}

	public ToolExecutionResult execute(ToolDescriptor tool, Map<String, Object> arguments) {
		try {
			String baseUrl = extractBaseUrl(tool);
			String method = extractMethod(tool);
			String path = extractPath(tool);
			Map<String, String> headers = extractHeaders(tool);
			Object body = buildBody(tool, arguments);

			HttpHeaders httpHeaders = new HttpHeaders();
			httpHeaders.setContentType(MediaType.APPLICATION_JSON);
			headers.forEach(httpHeaders::set);

			HttpMethod httpMethod = HttpMethod.valueOf(method);
			HttpEntity<?> entity;
			if ("GET".equals(method) || "HEAD".equals(method)) {
				entity = new HttpEntity<>(httpHeaders);
			}
			else {
				entity = new HttpEntity<>(body, httpHeaders);
			}

			String response;
			try {
				response = restTemplate.exchange(baseUrl + path, httpMethod, entity, String.class).getBody();
			}
			catch (HttpStatusCodeException ex) {
				response = ex.getResponseBodyAsString();
			}

			String summary = "HTTP %s %s completed".formatted(method, path);
			return new ToolExecutionResult(true, response, summary, null);
		}
		catch (Exception exception) {
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
}
