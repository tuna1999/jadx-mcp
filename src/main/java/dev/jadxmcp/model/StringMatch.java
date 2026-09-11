package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One string constant found by search_strings.
 *
 * <p>Indexed results (Phase 2) carry {@code methodId}/{@code methodName} and no
 * {@code line}; Phase 1 decompiled-scan results carry {@code line} and no
 * method fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StringMatch(
		String value,
		String classId,
		String className,
		Integer line,
		String methodId,
		String methodName) {
}
