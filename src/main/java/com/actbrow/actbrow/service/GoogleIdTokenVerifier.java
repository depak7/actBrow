package com.actbrow.actbrow.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.actbrow.actbrow.config.GoogleOAuthProperties;

@Component
public class GoogleIdTokenVerifier {

	private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
		new ParameterizedTypeReference<>() {
		};

	private final RestTemplate restTemplate;
	private final GoogleOAuthProperties properties;

	public GoogleIdTokenVerifier(GoogleOAuthProperties properties) {
		this.properties = properties;
		this.restTemplate = createRestTemplate(Duration.ofSeconds(15));
	}

	private static RestTemplate createRestTemplate(Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		return new RestTemplate(factory);
	}

	/**
	 * Validates the Google Sign-In JWT using Google's tokeninfo endpoint and returns claims
	 * ({@code sub}, {@code email}, {@code name}, {@code picture}, etc.).
	 */
	public Map<String, Object> verifyAndDecode(String idToken) {
		if (idToken == null || idToken.isBlank()) {
			throw new IllegalArgumentException("idToken is required");
		}
		try {
			URI uri = UriComponentsBuilder.fromUriString("https://oauth2.googleapis.com/tokeninfo")
				.queryParam("id_token", idToken)
				.build()
				.toUri();
			Map<String, Object> body = restTemplate.exchange(uri, HttpMethod.GET, null, MAP_TYPE).getBody();
			if (body == null || body.containsKey("error")) {
				throw new IllegalArgumentException("Invalid Google token");
			}
			String clientId = properties.clientId();
			if (clientId != null && !clientId.isBlank()) {
				Object aud = body.get("aud");
				if (aud instanceof String s && !s.equals(clientId)) {
					throw new IllegalArgumentException("Token audience mismatch");
				}
				if (aud instanceof List<?> list && !list.contains(clientId)) {
					throw new IllegalArgumentException("Token audience mismatch");
				}
			}
			return body;
		}
		catch (HttpStatusCodeException e) {
			throw new IllegalArgumentException("Google token verification failed", e);
		}
	}
}
