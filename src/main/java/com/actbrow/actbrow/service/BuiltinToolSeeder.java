package com.actbrow.actbrow.service;

import java.util.List;

import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.actbrow.actbrow.repository.AssistantRepository;

@Component
public class BuiltinToolSeeder implements ApplicationRunner {

	private static final List<String> RETIRED_TOOL_KEYS = List.of(
		"dom.click", "dom.type", "dom.read", "dom.query",
		"api.get", "api.post", "api.put", "api.delete");

	private final BuiltinToolCatalog builtInToolCatalog;
	private final ToolService toolService;
	private final AssistantRepository assistantRepository;

	public BuiltinToolSeeder(BuiltinToolCatalog builtInToolCatalog, ToolService toolService,
		AssistantRepository assistantRepository) {
		this.builtInToolCatalog = builtInToolCatalog;
		this.toolService = toolService;
		this.assistantRepository = assistantRepository;
	}

	@Override
	public void run(org.springframework.boot.ApplicationArguments args) {
		for (String key : RETIRED_TOOL_KEYS) {
			toolService.deleteByKeyIfPresent(key);
		}
		for (var tool : builtInToolCatalog.builtInClientTools()) {
			toolService.upsertByKey(tool);
		}
		assistantRepository.findAll()
			.forEach(assistant -> toolService.attachBuiltInClientTools(assistant.getId()));
	}
}
