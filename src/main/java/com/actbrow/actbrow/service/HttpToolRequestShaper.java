package com.actbrow.actbrow.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a flat tool-argument map into a concrete HTTP request for tools generated from an
 * OpenAPI spec. Substitutes {pathParam} placeholders, appends a query string, lifts header
 * params out, and leaves the remaining arguments as the request body.
 *
 * <p>The {@code parameters} metadata is a list of {name, in, required} records where
 * {@code in} is one of {@code path}, {@code query}, or {@code header}. Anything not declared
 * there is treated as a request-body field — except that, for tools whose path template still
 * contains {@code {placeholder}} segments, matching arguments are substituted into the path even
 * when no {@code parameters} metadata declares them (covers synced/hand-written tools). Leftover
 * arguments become the request body, or — for methods that carry no body (GET/HEAD) — the query
 * string, since a GET body would otherwise be silently dropped.
 */
public final class HttpToolRequestShaper {

	private HttpToolRequestShaper() {
	}

	private static final java.util.regex.Pattern PATH_PLACEHOLDER =
		java.util.regex.Pattern.compile("\\{([^}/]+)\\}");

	public record ShapedRequest(String path, Map<String, Object> body, Map<String, String> headers) {
	}

	@SuppressWarnings("unchecked")
	public static ShapedRequest shape(String method, String pathTemplate, Object parametersMetadata,
		Map<String, Object> arguments) {
		Map<String, Object> args = arguments == null ? Map.of() : arguments;
		String path = pathTemplate == null ? "/" : pathTemplate;
		Map<String, String> headers = new LinkedHashMap<>();
		StringBuilder query = new StringBuilder();
		Set<String> consumed = new java.util.HashSet<>();

		if (parametersMetadata instanceof List<?> params) {
			for (Object raw : params) {
				if (!(raw instanceof Map)) {
					continue;
				}
				Map<String, Object> param = (Map<String, Object>) raw;
				Object nameObj = param.get("name");
				Object inObj = param.get("in");
				if (nameObj == null || inObj == null) {
					continue;
				}
				String name = nameObj.toString();
				String in = inObj.toString();
				if (!args.containsKey(name)) {
					continue;
				}
				Object value = args.get(name);
				switch (in) {
					case "path" -> {
						path = path.replace("{" + name + "}", encodePathSegment(stringValue(value)));
						consumed.add(name);
					}
					case "query" -> {
						appendQuery(query, name, value);
						consumed.add(name);
					}
					case "header" -> {
						headers.put(name, stringValue(value));
						consumed.add(name);
					}
					default -> {
						// body parameter (e.g. OpenAPI v2 "in: body") — leave for the body map
					}
				}
			}
		}

		// Substitute any remaining {placeholder} from matching args, even when undeclared in
		// parameters metadata (synced tools store the template path but not the param list).
		java.util.regex.Matcher matcher = PATH_PLACEHOLDER.matcher(path);
		while (matcher.find()) {
			String name = matcher.group(1);
			if (args.containsKey(name)) {
				path = path.replace("{" + name + "}", encodePathSegment(stringValue(args.get(name))));
				consumed.add(name);
				matcher = PATH_PLACEHOLDER.matcher(path);
			}
		}

		boolean carriesBody = !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method));
		Map<String, Object> body = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : args.entrySet()) {
			if (consumed.contains(entry.getKey())) {
				continue;
			}
			if (carriesBody) {
				body.put(entry.getKey(), entry.getValue());
			}
			else {
				appendQuery(query, entry.getKey(), entry.getValue());
			}
		}

		String separator = path.indexOf('?') >= 0 ? "&" : "?";
		String finalPath = query.length() == 0 ? path : path + separator + query;
		return new ShapedRequest(finalPath, body, headers);
	}

	private static void appendQuery(StringBuilder query, String name, Object value) {
		if (value instanceof List<?> list) {
			for (Object item : list) {
				appendQueryPair(query, name, stringValue(item));
			}
		}
		else {
			appendQueryPair(query, name, stringValue(value));
		}
	}

	private static void appendQueryPair(StringBuilder query, String name, String value) {
		if (query.length() > 0) {
			query.append('&');
		}
		query.append(encode(name)).append('=').append(encode(value));
	}

	private static String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String encodePathSegment(String value) {
		// URLEncoder targets query strings (space -> '+'); path segments need %20.
		return encode(value).replace("+", "%20");
	}
}
