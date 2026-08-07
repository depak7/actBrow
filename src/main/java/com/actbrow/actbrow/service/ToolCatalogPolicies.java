package com.actbrow.actbrow.service;

import com.actbrow.actbrow.api.dto.ToolResponse;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.model.ToolDefinitionEntity;
import com.actbrow.actbrow.model.ToolType;

/**
 * Platform-seeded catalog tools vs user-managed tools.
 */
public final class ToolCatalogPolicies {

	private ToolCatalogPolicies() {
	}

	public static boolean isHiddenFromAssistantManagementList(ToolDefinitionEntity entity) {
		return isPlatformCatalogTool(entity.getType(), entity.getKey(), entity.getExecutorRef());
	}

	public static boolean isPlatformCatalogTool(ToolType type, String key, String executorRef) {
		if (type == ToolType.BUILD_IN) {
			return true;
		}
		if (executorRef == null || key == null || !key.equals(executorRef)) {
			return false;
		}
		if (type == ToolType.CLIENT) {
			return isClientSideCatalogExecutor(executorRef);
		}
		return false;
	}

	public static boolean isBuiltInAttachmentCandidate(ToolResponse tool) {
		if (tool.executorRef() == null || !tool.key().equals(tool.executorRef())) {
			return false;
		}
		if (tool.type() == ToolType.BUILD_IN) {
			return isClientSideCatalogExecutor(tool.executorRef()) || isServerSideCatalogExecutor(tool.executorRef());
		}
		if (tool.type() == ToolType.CLIENT) {
			return isClientSideCatalogExecutor(tool.executorRef());
		}
		return false;
	}

	/** @deprecated use {@link #isBuiltInAttachmentCandidate} */
	@Deprecated
	public static boolean isBuiltInClientAttachmentCandidate(ToolResponse tool) {
		return isBuiltInAttachmentCandidate(tool);
	}

	public static boolean executesAsKnowledgeSearch(ToolType type, String executorRef) {
		return type == ToolType.BUILD_IN && "knowledge.search".equals(executorRef);
	}

	public static boolean executesAsClientPendingTool(ToolType type, String executorRef) {
		if (type == ToolType.CLIENT) {
			return true;
		}
		if (type == ToolType.BUILD_IN) {
			return executorRef != null && isClientSideCatalogExecutor(executorRef);
		}
		return false;
	}

	public static boolean executesAsBrowserHttpTool(ToolDescriptor tool) {
		if (tool.type() != ToolType.SERVER_HTTP) {
			return false;
		}
		Object execution = tool.metadata() == null ? null : tool.metadata().get("execution");
		return execution != null && "browser".equalsIgnoreCase(execution.toString());
	}

	public static boolean executesAsHttpTool(ToolType type, String executorRef) {
		return type == ToolType.SERVER_HTTP;
	}

	public static boolean executesAsMcpTool(ToolType type, String executorRef) {
		return type == ToolType.MCP || "mcp.call".equals(executorRef);
	}

	/**
	 * Which specialist owns this tool. SHARED tools are offered in every specialist; BROWSER and API
	 * tools only when that specialist is active.
	 */
	public static ToolDomain domainOf(ToolDescriptor tool) {
		if (tool == null) {
			return ToolDomain.SHARED;
		}
		String key = tool.key();
		if (ProgressiveToolDisclosureService.isMetaOrAgentSwitchTool(key)
			|| executesAsKnowledgeSearch(tool.type(), tool.executorRef())) {
			return ToolDomain.SHARED;
		}
		if (executesAsClientPendingTool(tool.type(), tool.executorRef())
			|| executesAsBrowserHttpTool(tool)) {
			return ToolDomain.BROWSER;
		}
		if (executesAsMcpTool(tool.type(), tool.executorRef())) {
			return ToolDomain.API;
		}
		if (executesAsHttpTool(tool.type(), tool.executorRef())) {
			// Non-browser SERVER_HTTP runs on the server — API specialist.
			return ToolDomain.API;
		}
		// Unknown custom types default to API so operator-added tools stay with the API specialist.
		if (tool.type() == ToolType.BUILD_IN) {
			return ToolDomain.BROWSER;
		}
		return ToolDomain.API;
	}

	/**
	 * Server-side tools that do not park the run on the client may run concurrently on virtual
	 * threads when the model emits multiple calls in one step.
	 */
	public static boolean isParallelSafe(ToolDescriptor tool) {
		if (tool == null) {
			return false;
		}
		// Meta tools mutate specialist/active-set state — never fan out beside other calls.
		if (ProgressiveToolDisclosureService.isMetaOrAgentSwitchTool(tool.key())) {
			return false;
		}
		if (executesAsClientPendingTool(tool.type(), tool.executorRef())
			|| executesAsBrowserHttpTool(tool)) {
			return false;
		}
		// Navigate is client-pending above; keep an explicit guard for key-only matches.
		String key = tool.key();
		if ("app.navigate".equals(key) || "app.navigate".equals(tool.executorRef())) {
			return false;
		}
		return true;
	}

	private static boolean isClientSideCatalogExecutor(String executorRef) {
		return "app.navigate".equals(executorRef)
			|| "path.find".equals(executorRef)
			|| "page.observe".equals(executorRef)
			|| "page.screenshot".equals(executorRef);
	}

	private static boolean isServerSideCatalogExecutor(String executorRef) {
		return "knowledge.search".equals(executorRef)
			|| ProgressiveToolDisclosureService.TOOL_SEARCH.equals(executorRef)
			|| ProgressiveToolDisclosureService.TOOL_ACTIVATE.equals(executorRef)
			|| ProgressiveToolDisclosureService.AGENT_USE_BROWSER.equals(executorRef)
			|| ProgressiveToolDisclosureService.AGENT_USE_API.equals(executorRef);
	}
}
