package com.actbrow.actbrow.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.actbrow.actbrow.config.LlmProperties;
import com.actbrow.actbrow.model.ConversationMessageEntity;

@Component
@Primary
public class RoutingModelProvider implements ModelProvider {

	private final Map<String, ModelProvider> providersByKey;
	private final String defaultProvider;

	public RoutingModelProvider(List<ModelProvider> providers, LlmProperties properties) {
		this.providersByKey = providers.stream()
			.filter(provider -> !(provider instanceof RoutingModelProvider))
			.collect(Collectors.toMap(provider -> provider.providerKey().toLowerCase(Locale.ROOT), Function.identity()));
		this.defaultProvider = properties.defaultProvider() == null || properties.defaultProvider().isBlank()
			? "gemini"
			: properties.defaultProvider().toLowerCase(Locale.ROOT);
	}

	@Override
	public String providerKey() {
		return "routing";
	}

	@Override
	public ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex) {
		ResolvedProvider resolvedProvider = resolveProvider(model);
		ModelProvider provider = providersByKey.get(resolvedProvider.providerKey());
		if (provider == null) {
			throw new IllegalArgumentException("Unknown model provider: " + resolvedProvider.providerKey());
		}
		return provider.decideNextStep(resolvedProvider.modelName(), systemPrompt, messages, tools, stepIndex);
	}

	private ResolvedProvider resolveProvider(String configuredModel) {
		if (configuredModel != null && configuredModel.contains(":")) {
			String[] parts = configuredModel.split(":", 2);
			String providerKey = parts[0].trim().toLowerCase(Locale.ROOT);
			if (providersByKey.containsKey(providerKey)) {
				return new ResolvedProvider(providerKey, parts[1].trim());
			}
		}
		return new ResolvedProvider(defaultProvider, configuredModel);
	}

	private record ResolvedProvider(String providerKey, String modelName) {
	}
}
