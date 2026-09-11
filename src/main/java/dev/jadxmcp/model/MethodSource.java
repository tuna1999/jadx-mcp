package dev.jadxmcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Decompiled source of a single method. Primary code-reading API for agents. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MethodSource(
		String id,
		String name,
		String className,
		String signature,
		String returnType,
		List<String> argumentTypes,
		String access,
		String source,
		boolean noCode,
		boolean truncated,
		int totalChars,
		int returnedChars,
		Integer maxChars) {
}
