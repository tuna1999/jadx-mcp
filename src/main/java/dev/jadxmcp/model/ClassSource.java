package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Decompiled source of one class with explicit truncation metadata. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClassSource(
		String id,
		String name,
		String source,
		int totalChars,
		int returnedChars,
		boolean truncated,
		Integer maxChars) {
}
