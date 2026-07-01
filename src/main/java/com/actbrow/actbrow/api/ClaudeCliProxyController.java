package com.actbrow.actbrow.api;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.service.ClaudeCliService;

@RestController
@RequestMapping("/claude/v1")
public class ClaudeCliProxyController {

	private final ClaudeCliService claudeCliService;

	public ClaudeCliProxyController(ClaudeCliService claudeCliService) {
		this.claudeCliService = claudeCliService;
	}

	@PostMapping("/chat/completions")
	public Map<String, Object> chatCompletions(@RequestBody Map<String, Object> request) {
		return claudeCliService.complete(request);
	}

}
