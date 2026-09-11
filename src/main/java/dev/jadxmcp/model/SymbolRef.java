package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Reference to a symbol: class, method or field. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SymbolRef(
		String type,
		String id,
		String name,
		String className) {
}
