package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.model.AssistantSafetyFlagEntity;
import com.actbrow.actbrow.repository.AssistantSafetyFlagRepository;

class FeatureFlagPersistenceTests {

	private final List<AssistantSafetyFlagEntity> table = new ArrayList<>();
	private final AtomicLong clock = new AtomicLong();
	private AssistantSafetyFlagRepository repository;

	@BeforeEach
	void setUp() {
		repository = mock(AssistantSafetyFlagRepository.class);
		when(repository.findAllByAssistantId(any())).thenAnswer(inv -> table.stream()
			.filter(r -> r.getAssistantId().equals(inv.getArgument(0))).toList());
		when(repository.findByAssistantIdAndFlag(any(), any())).thenAnswer(inv -> table.stream()
			.filter(r -> r.getAssistantId().equals(inv.getArgument(0)) && r.getFlag().equals(inv.getArgument(1)))
			.findFirst());
		when(repository.save(any())).thenAnswer(inv -> {
			AssistantSafetyFlagEntity row = inv.getArgument(0);
			if (!table.contains(row)) {
				table.add(row);
			}
			return row;
		});
	}

	private FeatureFlagService service() {
		return new FeatureFlagService(true, false, repository, clock::get);
	}

	@Test
	void killSwitchSurvivesARestart() {
		service().setAssistantFlag("a1", FeatureFlagService.TOOLS_ENABLED, false, "operator-1");

		// A restart builds a fresh service over the same table; the baseline says tools are on.
		FeatureFlagService restarted = service();
		assertThat(restarted.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isFalse();
		// Assistants that were never touched still get the baseline.
		assertThat(restarted.isEnabled("a2", FeatureFlagService.TOOLS_ENABLED)).isTrue();
	}

	@Test
	void reEnablingIsAlsoPersistedAsASingleRow() {
		FeatureFlagService flags = service();
		flags.setAssistantFlag("a1", FeatureFlagService.TOOLS_ENABLED, false, "operator-1");
		flags.setAssistantFlag("a1", FeatureFlagService.TOOLS_ENABLED, true, "operator-2");

		assertThat(service().isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isTrue();
		assertThat(table).hasSize(1);
		assertThat(table.get(0).getUpdatedBy()).isEqualTo("operator-2");
	}

	@Test
	void anotherInstanceSeesTheFlipOnceTheCacheExpires() {
		FeatureFlagService otherInstance = service();
		assertThat(otherInstance.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isTrue();

		service().setAssistantFlag("a1", FeatureFlagService.TOOLS_ENABLED, false, "operator-1");
		// Within the TTL the other instance may still serve its cached read...
		assertThat(otherInstance.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isTrue();

		clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(FeatureFlagService.CACHE_TTL_MS + 1));
		// ...but no longer than that.
		assertThat(otherInstance.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isFalse();
	}

	@Test
	void theInstanceThatFlipsItSeesTheChangeImmediately() {
		FeatureFlagService flags = service();
		assertThat(flags.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isTrue();
		flags.setAssistantFlag("a1", FeatureFlagService.TOOLS_ENABLED, false, "operator-1");
		assertThat(flags.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isFalse();
	}

	@Test
	void aDatabaseBlipDoesNotFailOpen() {
		FeatureFlagService flags = service();
		flags.setAssistantFlag("a1", FeatureFlagService.TOOLS_ENABLED, false, "operator-1");
		assertThat(flags.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isFalse();

		when(repository.findAllByAssistantId(any())).thenThrow(new IllegalStateException("db down"));
		clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(FeatureFlagService.CACHE_TTL_MS + 1));
		// Falling back to the baseline here would silently re-enable tools during an outage.
		assertThat(flags.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isFalse();
	}

	@Test
	void aFailedWriteIsReportedAndNotAppliedInMemory() {
		// doThrow, not when(...): when(save(any())) would invoke the fake save above with a null row.
		doThrow(new IllegalStateException("db down")).when(repository).save(any());
		FeatureFlagService flags = service();

		assertThatThrownBy(() -> flags.setAssistantFlag("a1", FeatureFlagService.TOOLS_ENABLED, false, "op"))
			.isInstanceOf(IllegalStateException.class);
		// The operator gets an error, and nothing pretends the switch is off.
		assertThat(flags.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isTrue();
		assertThat(table).isEmpty();
	}
}
