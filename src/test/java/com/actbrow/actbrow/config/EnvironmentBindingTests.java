package com.actbrow.actbrow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The env var names in .env.example and the README are the contract operators follow. Spring's
 * relaxed binding does not map those names onto the property keys the code reads, so without an
 * explicit mapping in application.properties they were silently ignored.
 *
 * <p>Reads src/main/resources directly: the test classpath has its own application.properties that
 * would otherwise shadow the file under test.
 */
class EnvironmentBindingTests {

	private static StandardEnvironment env(Map<String, Object> overrides) throws Exception {
		Properties main = new Properties();
		try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
			main.load(in);
		}
		StandardEnvironment env = new StandardEnvironment();
		// Drop the real OS env and system properties so a developer's own keys cannot skew the result.
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		env.getPropertySources().addFirst(new MapPropertySource("env", overrides));
		env.getPropertySources().addLast(new PropertiesPropertySource("application", main));
		return env;
	}

	@Test
	void documentedKillSwitchVariableReachesTheSafetyBaseline() throws Exception {
		var restricted = env(Map.of("ACTBROW_TOOLS_ENABLED", "false", "ACTBROW_SHADOW_MODE", "true"));
		assertThat(restricted.getProperty("actbrow.flags.tools-enabled")).isEqualTo("false");
		assertThat(restricted.getProperty("actbrow.flags.shadow-mode")).isEqualTo("true");

		var defaults = env(Map.of());
		assertThat(defaults.getProperty("actbrow.flags.tools-enabled")).isEqualTo("true");
		assertThat(defaults.getProperty("actbrow.flags.shadow-mode")).isEqualTo("false");
	}

	@Test
	void noModelApiKeyIsCommittedAsAFallback() throws Exception {
		assertThat(env(Map.of()).getProperty("spring.ai.openai.api-key")).isEqualTo("unset");
		assertThat(env(Map.of("GEMINI_API_KEY", "real-key")).getProperty("spring.ai.openai.api-key"))
			.isEqualTo("real-key");
	}
}
