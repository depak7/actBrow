package com.actbrow.actbrow.service;

import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.actbrow.actbrow.repository.AssistantRepository;

@Component
public class BuiltinToolSeeder implements ApplicationRunner {

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
		for (var tool : builtInToolCatalog.builtInClientTools()) {
			toolService.upsertByKey(tool);
		}
		assistantRepository.findAll()
			.forEach(assistant -> toolService.attachBuiltInClientTools(assistant.getId()));
	}
}
