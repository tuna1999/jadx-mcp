package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Field row inside a class outline. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldEntry(
		String id,
		String name,
		String type,
		String access) {
}
