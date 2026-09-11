package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Minimal class reference used in list/search results. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClassEntry(
		String id,
		String name,
		String packageName,
		Boolean isInner) {
}
