package com.actbrow.actbrow.model;

public enum ToolType {
	CLIENT,
	/** Platform catalog tools (dom.query, api.get, …) auto-attached to assistants; hidden from management APIs. */
	BUILD_IN,
	SERVER_BUILTIN,
	SERVER_HTTP
}
