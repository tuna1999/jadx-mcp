package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Method row inside outlines and search results. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MethodEntry(
		String id,
		String name,
		String className,
		String signature,
		String access) {
}
