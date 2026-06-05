package com.actbrow.actbrow.api.dto;

import java.util.List;

public record ImportApiSpecResponse(
	String integrationId,
	String name,
	int created,
	int updated,
	int removed,
	List<String> toolKeys
) {
}
