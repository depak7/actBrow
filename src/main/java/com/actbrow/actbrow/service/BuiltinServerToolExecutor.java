package com.actbrow.actbrow.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class BuiltinServerToolExecutor {

    private final ObjectMapper objectMapper;

    public BuiltinServerToolExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ToolExecutionResult execute(ToolDescriptor tool, Map<String, Object> arguments) {
        String summary = "Executed server tool " + tool.key();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool.key());
        payload.put("executorRef", tool.executorRef());
        payload.put("arguments", arguments);
        try {
            return new ToolExecutionResult(true, objectMapper.writeValueAsString(payload), summary, null);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize server tool output", exception);
        }
    }
}
