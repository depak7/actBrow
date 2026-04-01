package com.actbrow.actbrow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonSchemaValidator {

	private final ObjectMapper objectMapper;

	public JsonSchemaValidator(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public void assertValidJsonObject(String payload, String fieldName) {
		try {
			JsonNode node = objectMapper.readTree(payload);
			if (node == null || !node.isObject()) {
				throw new IllegalArgumentException(fieldName + " must be a JSON object");
			}
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException(fieldName + " must be valid JSON", exception);
		}
	}

	public String normalizeObject(JsonNode node, String fieldName) {
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException(fieldName + " must be a JSON object");
		}
		try {
			return objectMapper.writeValueAsString(node);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException(fieldName + " must be serializable JSON", exception);
		}
	}

	public String normalizeObject(Map<String, Object> value, String fieldName) {
		try {
			JsonNode node = objectMapper.valueToTree(value);
			if (node == null || !node.isObject()) {
				throw new IllegalArgumentException(fieldName + " must be a JSON object");
			}
			return objectMapper.writeValueAsString(node);
		}
		catch (IllegalArgumentException | JsonProcessingException exception) {
			throw new IllegalArgumentException(fieldName + " must be a JSON object", exception);
		}
	}

	public Map<String, Object> parseObject(String payload) {
		try {
			return objectMapper.readValue(payload, objectMapper.getTypeFactory()
				.constructMapType(Map.class, String.class, Object.class));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Stored JSON is invalid", exception);
		}
	}
}
