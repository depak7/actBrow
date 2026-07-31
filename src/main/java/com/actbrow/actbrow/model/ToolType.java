package com.actbrow.actbrow.model;

public enum ToolType {
	CLIENT,
	/** Platform catalog tools (path.find, page.screenshot, app.navigate) auto-attached to assistants; hidden from management APIs. */
	BUILD_IN,
	SERVER_HTTP,
	/** Tools imported from a customer-connected MCP server; executed server-side via JSON-RPC. */
	MCP
}
