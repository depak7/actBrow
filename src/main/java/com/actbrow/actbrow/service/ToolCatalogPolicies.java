package com.actbrow.actbrow.service;

import com.actbrow.actbrow.api.dto.ToolResponse;
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
			return executorRef.startsWith("dom.") || executorRef.startsWith("page.") || "app.navigate".equals(executorRef);
		}
		if (type == ToolType.SERVER_HTTP) {
			return executorRef.startsWith("api.");
		}
		return false;
	}

	public static boolean isBuiltInClientAttachmentCandidate(ToolResponse tool) {
		if (tool.executorRef() == null || !tool.key().equals(tool.executorRef())) {
			return false;
		}
		if (tool.type() == ToolType.BUILD_IN || tool.type() == ToolType.CLIENT) {
			return isClientSideCatalogExecutor(tool.executorRef());
		}
		return false;
	}

	public static boolean isHttpBuiltInAttachmentCandidate(ToolResponse tool) {
		if (tool.executorRef() == null || !tool.key().equals(tool.executorRef())) {
			return false;
		}
		if (tool.type() == ToolType.BUILD_IN || tool.type() == ToolType.SERVER_HTTP) {
			return tool.executorRef().startsWith("api.");
		}
		return false;
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

	public static boolean executesAsHttpTool(ToolType type, String executorRef) {
		if (type == ToolType.SERVER_HTTP) {
			return true;
		}
		if (type == ToolType.BUILD_IN) {
			return executorRef != null && executorRef.startsWith("api.");
		}
		return false;
	}

	private static boolean isClientSideCatalogExecutor(String executorRef) {
		return executorRef.startsWith("dom.") || executorRef.startsWith("page.") || "app.navigate".equals(executorRef);
	}
}
