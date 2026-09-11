package dev.jadxmcp.util;

import tools.jackson.databind.json.JsonMapper;

/** Shared JSON mapper (Jackson 3, same stack as the MCP SDK). */
public final class Json {

	public static final JsonMapper MAPPER = JsonMapper.builder().build();

	private Json() {
	}

	public static String write(Object value) {
		return MAPPER.writeValueAsString(value);
	}
}
