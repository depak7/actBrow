package com.actbrow.actbrow.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.config.ActbrowSnapshotProperties;

/**
 * Public runtime config for the browser SDK. Returns settings the SDK needs to know
 * before any user interaction (e.g. which capture mode page.screenshot should use).
 *
 * Public route — no API key required. The SDK fetches this once at boot.
 */
@RestController
@RequestMapping("/v1/widget")
public class WidgetConfigController {

	private final ActbrowSnapshotProperties snapshotProperties;

	public WidgetConfigController(ActbrowSnapshotProperties snapshotProperties) {
		this.snapshotProperties = snapshotProperties;
	}

	@GetMapping("/config")
	public Map<String, Object> config() {
		return Map.of("snapshotMode", snapshotProperties.mode());
	}
}
