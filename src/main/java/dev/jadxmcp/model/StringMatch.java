package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One string constant found by search_strings. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StringMatch(
		String value,
		String classId,
		String className,
		Integer line) {
}
