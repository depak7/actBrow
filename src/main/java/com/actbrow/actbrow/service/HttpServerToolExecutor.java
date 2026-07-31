package com.actbrow.actbrow.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
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
	private final OutboundUrlPolicy outboundUrlPolicy;

	public HttpServerToolExecutor(OutboundUrlPolicy outboundUrlPolicy) {
		this.outboundUrlPolicy = outboundUrlPolicy;
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(Duration.ofSeconds(30));
		this.restTemplate = new RestTemplate(factory);
	}

	public ToolExecutionResult execute(ToolDescriptor tool, Map<String, Object> arguments) {
		try {
			String baseUrl = extractBaseUrl(tool);
			String method = extractMethod(tool);
			String pathTemplate = extractPath(tool);
			HttpToolRequestShaper.ShapedRequest shaped = HttpToolRequestShaper.shape(method, pathTemplate,
				tool.metadata().get("parameters"), arguments);
			String path = shaped.path();

			URI target = outboundUrlPolicy.validateHttpUrl(baseUrl + path);

			HttpHeaders httpHeaders = new HttpHeaders();
			httpHeaders.setContentType(MediaType.APPLICATION_JSON);
			Map<String, String> metadataHeaders = extractHeaders(tool);
			outboundUrlPolicy.validateHeaders(metadataHeaders);
			outboundUrlPolicy.validateHeaders(shaped.headers());
			metadataHeaders.forEach(httpHeaders::set);
			shaped.headers().forEach(httpHeaders::set);

			HttpMethod httpMethod = HttpMethod.valueOf(method);
			HttpEntity<?> entity;
			if ("GET".equals(method) || "HEAD".equals(method)) {
				entity = new HttpEntity<>(httpHeaders);
			}
			else {
				entity = new HttpEntity<>(shaped.body(), httpHeaders);
			}

			String response;
			int statusCode;
			try {
				var responseEntity = restTemplate.exchange(target, httpMethod, entity, String.class);
				statusCode = responseEntity.getStatusCode().value();
				response = responseEntity.getBody();
			}
			catch (HttpStatusCodeException ex) {
				statusCode = ex.getStatusCode().value();
				response = ex.getResponseBodyAsString();
			}
			if (response != null && response.length() > 250_000) {
				return new ToolExecutionResult(false, null, "HTTP response too large",
					"Response exceeded 250KB limit");
			}

			if (statusCode >= 200 && statusCode < 300) {
				String summary = "HTTP %s %s returned %d".formatted(method, path, statusCode);
				return new ToolExecutionResult(true, response, summary, null);
			}

			// Non-2xx: keep the body so the model can inspect the error payload, but report failure.
			// The status code must appear in the error text so FailureClassifier can bucket it.
			String summary = "HTTP %s %s failed with status %d".formatted(method, path, statusCode);
			String excerpt = response == null ? "" : response.substring(0, Math.min(response.length(), 500));
			String error = excerpt.isBlank()
				? "HTTP status %d".formatted(statusCode)
				: "HTTP status %d: %s".formatted(statusCode, excerpt);
			return new ToolExecutionResult(false, response, summary, error);
		}
		catch (Exception exception) {
			return new ToolExecutionResult(false, null, "HTTP request failed", exception.getMessage());
		}
	}

	private String extractBaseUrl(ToolDescriptor tool) {
		Object baseUrl = tool.metadata().get("baseUrl");
		return baseUrl != null ? baseUrl.toString() : "";
	}

	private String extractMethod(ToolDescriptor tool) {
		Object method = tool.metadata().get("method");
		return method != null ? method.toString().toUpperCase() : "GET";
	}

	private String extractPath(ToolDescriptor tool) {
		Object path = tool.metadata().get("path");
		return path != null ? path.toString() : "/";
	}

	private Map<String, String> extractHeaders(ToolDescriptor tool) {
		Object headers = tool.metadata().get("headers");
		Map<String, String> result = new LinkedHashMap<>();
		if (headers instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null) {
					result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
				}
			}
		}
		return result;
	}
}
