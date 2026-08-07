package com.actbrow.actbrow.service;

/**
 * Specialist partitioning of the tool catalog. The run loop exposes one specialist at a time so the
 * model only sees browser tools <em>or</em> API tools (plus a small shared set), which stops
 * added/operator tools from being invented while the model is in the wrong mode.
 */
public enum ToolDomain {

	/** Client / in-page tools: navigate, observe, path, screenshots, browser-executed HTTP. */
	BROWSER,

	/** Server-side integrations: SERVER_HTTP (server execution), MCP. */
	API,

	/** Always available across specialists: knowledge, search/activate, agent switch. */
	SHARED
}
