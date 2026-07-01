package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.model.SideEffectLevel;
import com.actbrow.actbrow.model.ToolType;

class ToolContractTests {

	private ToolDescriptor toolWithMetadata(Map<String, Object> metadata) {
		return new ToolDescriptor("tool-1", "orders.act", "Act", "{}", ToolType.SERVER_HTTP, "orders.act",
			Map.of(), metadata);
	}

	@Test
	void defaultsAreReadOnlyAndSafe() {
		ToolContract contract = ToolContract.from(toolWithMetadata(Map.of()));
		assertThat(contract.sideEffectLevel()).isEqualTo(SideEffectLevel.READ);
		assertThat(contract.isWrite()).isFalse();
		assertThat(contract.requiresPostVerification()).isFalse();
		assertThat(contract.idempotent()).isTrue();
		assertThat(contract.retryable()).isTrue();
	}

	@Test
	void writeRequiresPostVerificationByDefault() {
		ToolContract contract = ToolContract.from(toolWithMetadata(Map.of("sideEffectLevel", "WRITE")));
		assertThat(contract.isWrite()).isTrue();
		assertThat(contract.requiresPostVerification()).isTrue();
		// Writes default to non-idempotent.
		assertThat(contract.idempotent()).isFalse();
	}

	@Test
	void destructiveIsWrite() {
		ToolContract contract = ToolContract.from(toolWithMetadata(Map.of("sideEffectLevel", "destructive")));
		assertThat(contract.sideEffectLevel()).isEqualTo(SideEffectLevel.DESTRUCTIVE);
		assertThat(contract.isWrite()).isTrue();
	}

	@Test
	void verificationModeNoneOptsOut() {
		ToolContract contract = ToolContract.from(toolWithMetadata(Map.of(
			"sideEffectLevel", "WRITE", "verificationMode", "none")));
		assertThat(contract.requiresPostVerification()).isFalse();
	}

	@Test
	void readsMetadataFieldsIncludingLists() {
		ToolContract contract = ToolContract.from(toolWithMetadata(Map.of(
			"sideEffectLevel", "WRITE",
			"retryable", false,
			"idempotent", true,
			"verificationTool", "orders.get",
			"preconditions", List.of("cart not empty"),
			"commonFailureModes", List.of("payment declined", "out of stock"))));
		assertThat(contract.retryable()).isFalse();
		assertThat(contract.idempotent()).isTrue();
		assertThat(contract.verificationTool()).isEqualTo("orders.get");
		assertThat(contract.preconditions()).containsExactly("cart not empty");
		assertThat(contract.commonFailureModes()).containsExactly("payment declined", "out of stock");
	}

	@Test
	void unknownSideEffectFallsBackToRead() {
		ToolContract contract = ToolContract.from(toolWithMetadata(Map.of("sideEffectLevel", "weird")));
		assertThat(contract.sideEffectLevel()).isEqualTo(SideEffectLevel.READ);
	}
}
