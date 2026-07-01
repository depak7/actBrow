package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HttpToolRequestShaperTests {

	private static Map<String, Object> param(String name, String in) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("name", name);
		p.put("in", in);
		return p;
	}

	@Test
	void substitutesPathParamsBuildsQueryAndLeavesBody() {
		List<Map<String, Object>> params = List.of(
			param("petId", "path"),
			param("status", "query"));
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("petId", 7);
		args.put("status", "sold");
		args.put("name", "Rex");

		HttpToolRequestShaper.ShapedRequest shaped = HttpToolRequestShaper.shape("POST", "/pets/{petId}", params, args);

		assertThat(shaped.path()).isEqualTo("/pets/7?status=sold");
		assertThat(shaped.body()).containsOnlyKeys("name");
		assertThat(shaped.body().get("name")).isEqualTo("Rex");
		assertThat(shaped.headers()).isEmpty();
	}

	@Test
	void liftsHeaderParamsAndEncodesQuery() {
		List<Map<String, Object>> params = List.of(
			param("X-Trace", "header"),
			param("q", "query"));
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("X-Trace", "abc123");
		args.put("q", "a b&c");

		HttpToolRequestShaper.ShapedRequest shaped = HttpToolRequestShaper.shape("GET", "/search", params, args);

		assertThat(shaped.headers()).containsEntry("X-Trace", "abc123");
		assertThat(shaped.path()).isEqualTo("/search?q=a+b%26c");
		assertThat(shaped.body()).isEmpty();
	}

	@Test
	void nonGetWithoutParametersPutsWholeArgumentMapInBody() {
		Map<String, Object> args = Map.of("a", 1, "b", 2);

		HttpToolRequestShaper.ShapedRequest shaped = HttpToolRequestShaper.shape("POST", "/thing", null, args);

		assertThat(shaped.path()).isEqualTo("/thing");
		assertThat(shaped.body()).containsOnlyKeys("a", "b");
	}

	@Test
	void substitutesUndeclaredPathPlaceholderFromArguments() {
		// Synced tool: path template carries {websiteId} but no parameters metadata declares it.
		Map<String, Object> args = Map.of("websiteId", "a1b2c3");

		HttpToolRequestShaper.ShapedRequest shaped =
			HttpToolRequestShaper.shape("GET", "/api/websites/{websiteId}/active", null, args);

		assertThat(shaped.path()).isEqualTo("/api/websites/a1b2c3/active");
		assertThat(shaped.body()).isEmpty();
	}

	@Test
	void getLeftoverArgumentsBecomeQueryNotBody() {
		// GET would drop a body, so undeclared leftovers must land in the query string.
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("websiteId", "site-1");
		args.put("type", "referrer");
		args.put("startAt", "1000");

		HttpToolRequestShaper.ShapedRequest shaped =
			HttpToolRequestShaper.shape("GET", "/api/websites/{websiteId}/metrics", null, args);

		assertThat(shaped.body()).isEmpty();
		assertThat(shaped.path()).startsWith("/api/websites/site-1/metrics?");
		assertThat(shaped.path()).contains("type=referrer").contains("startAt=1000");
	}

	@Test
	void leavesUnmatchedPlaceholderUntouched() {
		HttpToolRequestShaper.ShapedRequest shaped =
			HttpToolRequestShaper.shape("GET", "/api/websites/{websiteId}/active", null, Map.of());

		assertThat(shaped.path()).isEqualTo("/api/websites/{websiteId}/active");
	}
}
