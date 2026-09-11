package dev.jadxmcp.tools;

import java.util.Map;
import java.util.function.Function;

/**
 * Transport-agnostic tool definition: MCP metadata plus a pure
 * arguments-to-payload function. Never touches MCP SDK types so the same tool
 * set can be served over STDIO and HTTP (and exercised directly in tests).
 */
public record ToolDefinition(
		String name,
		String description,
		String inputSchemaJson,
		Function<Map<String, Object>, Object> handler) {
}
