package com.actbrow.actbrow.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for customer-configured outbound HTTP and MCP URLs.
 */
@Component
public class OutboundUrlPolicy {

	private static final Set<String> BLOCKED_HEADER_NAMES = Set.of(
		"host", "content-length", "transfer-encoding", "connection", "keep-alive",
		"proxy-authenticate", "proxy-authorization", "te", "trailer", "upgrade",
		"cookie", "set-cookie");

	private final boolean httpsOnly;
	private final Set<String> hostAllowlist;

	public OutboundUrlPolicy(
		@Value("${actbrow.outbound.https-only:false}") boolean httpsOnly,
		@Value("${actbrow.outbound.host-allowlist:}") String hostAllowlistCsv) {
		this.httpsOnly = httpsOnly;
		this.hostAllowlist = parseAllowlist(hostAllowlistCsv);
	}

	public URI validateHttpUrl(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new IllegalArgumentException("URL is required");
		}
		URI uri;
		try {
			uri = URI.create(rawUrl.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Invalid URL");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			throw new IllegalArgumentException("Only http/https URLs are allowed");
		}
		if (httpsOnly && !"https".equals(scheme)) {
			throw new IllegalArgumentException("Only https URLs are allowed");
		}
		if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
			throw new IllegalArgumentException("URLs with embedded credentials are not allowed");
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("URL host is required");
		}
		String normalizedHost = host.toLowerCase(Locale.ROOT);
		if (!hostAllowlist.isEmpty() && !hostAllowlist.contains(normalizedHost)) {
			throw new IllegalArgumentException("Host is not on the outbound allowlist");
		}
		if (isBlockedHostName(normalizedHost)) {
			throw new IllegalArgumentException("URL host is not allowed");
		}
		try {
			for (InetAddress address : InetAddress.getAllByName(normalizedHost)) {
				if (isBlockedAddress(address)) {
					throw new IllegalArgumentException("URL resolves to a blocked address");
				}
			}
		}
		catch (UnknownHostException ex) {
			throw new IllegalArgumentException("Unable to resolve URL host");
		}
		return uri;
	}

	public void validateHeader(String name, String value) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Header name is required");
		}
		String normalized = name.trim().toLowerCase(Locale.ROOT);
		if (BLOCKED_HEADER_NAMES.contains(normalized) || normalized.startsWith("proxy-")) {
			throw new IllegalArgumentException("Header not allowed: " + name);
		}
		if (name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0 || name.indexOf(':') >= 0) {
			throw new IllegalArgumentException("Invalid header name");
		}
		if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
			throw new IllegalArgumentException("Invalid header value");
		}
	}

	public void validateHeaders(java.util.Map<String, String> headers) {
		if (headers == null) {
			return;
		}
		for (var entry : headers.entrySet()) {
			validateHeader(entry.getKey(), entry.getValue());
		}
	}

	private static boolean isBlockedHostName(String host) {
		return "localhost".equals(host)
			|| host.endsWith(".localhost")
			|| host.endsWith(".local")
			|| host.endsWith(".internal")
			|| "metadata.google.internal".equals(host)
			|| "metadata".equals(host);
	}

	private static boolean isBlockedAddress(InetAddress address) {
		return address.isAnyLocalAddress()
			|| address.isLoopbackAddress()
			|| address.isLinkLocalAddress()
			|| address.isSiteLocalAddress()
			|| address.isMulticastAddress()
			|| isCloudMetadata(address);
	}

	private static boolean isCloudMetadata(InetAddress address) {
		byte[] bytes = address.getAddress();
		// 169.254.169.254 and broader link-local already covered; also block 169.254.0.0/16 explicitly for clarity
		return bytes.length == 4 && (bytes[0] & 0xff) == 169 && (bytes[1] & 0xff) == 254;
	}

	private static Set<String> parseAllowlist(String csv) {
		if (csv == null || csv.isBlank()) {
			return Set.of();
		}
		Set<String> hosts = new java.util.LinkedHashSet<>();
		for (String part : csv.split(",")) {
			String host = part.trim().toLowerCase(Locale.ROOT);
			if (!host.isEmpty()) {
				hosts.add(host);
			}
		}
		return Set.copyOf(hosts);
	}
}
