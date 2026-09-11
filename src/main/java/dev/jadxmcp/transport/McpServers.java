package dev.jadxmcp.transport;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import dev.jadxmcp.model.ErrorCode;
import dev.jadxmcp.model.ErrorPayload;
import dev.jadxmcp.tools.ToolException;
import dev.jadxmcp.tools.ToolRegistry;
import dev.jadxmcp.util.Version;

/**
 * Shared MCP server factory: identical tools, capabilities and error semantics
 * for both transports. Contains no transport-specific I/O and no JADX logic.
 */
public final class McpServers {

	private static final Logger LOG = LoggerFactory.getLogger(McpServers.class);

	private McpServers() {
	}

	/** STDIO (single session). */
	public static McpSyncServer build(McpServerTransportProvider provider, ToolRegistry registry) {
		return McpServer.sync(provider)
				.serverInfo(serverInfo())
				.capabilities(capabilities())
				.instructions(instructions())
				.tools(syncSpecs(registry))
				.build();
	}

	/** Streamable HTTP (multi session). */
	public static McpSyncServer build(McpStreamableServerTransportProvider provider, ToolRegistry registry) {
		return McpServer.sync(provider)
				.serverInfo(serverInfo())
				.capabilities(capabilities())
				.instructions(instructions())
				.tools(syncSpecs(registry))
				.build();
	}

	private static McpSchema.Implementation serverInfo() {
		return McpSchema.Implementation.builder("jadx-mcp", Version.value()).build();
	}

	private static McpSchema.ServerCapabilities capabilities() {
		return new McpSchema.ServerCapabilities(
				null, null, null, null, null,
				new McpSchema.ServerCapabilities.ToolCapabilities(false));
	}

	private static String instructions() {
		return """
				JADX reverse-engineering tools. Typical flow: load_apk -> list_packages/list_classes \
				-> get_class_outline -> get_method_source -> get_xrefs. Search with search_classes/\
				search_methods/search_strings. Symbol ids are stable DEX-style identifiers like \
				'Lcom/example/Foo;->decrypt([B[B)[B'.""";
	}

	public static List<McpServerFeatures.SyncToolSpecification> syncSpecs(ToolRegistry registry) {
		return registry.definitions().stream()
				.map(def -> McpServerFeatures.SyncToolSpecification.builder()
						.tool(McpSchema.Tool
								.builder(def.name(), McpJsonDefaults.getMapper(), def.inputSchemaJson())
								.title(def.name())
								.description(def.description())
								.build())
						.callHandler((exchange, request) -> callTool(registry, def.name(), request.arguments()))
						.build())
				.toList();
	}

	/** Uniform success/error mapping for every tool call, on every transport. */
	public static McpSchema.CallToolResult callTool(ToolRegistry registry, String name,
			java.util.Map<String, Object> arguments) {
		try {
			Object payload = registry.call(name, arguments);
			return McpSchema.CallToolResult.builder()
					.addTextContent(ToolRegistry.toJson(payload))
					.structuredContent(payload)
					.isError(false)
					.build();
		} catch (ToolException e) {
			LOG.debug("tool '{}' failed: {} ({})", name, e.getMessage(), e.code());
			ErrorPayload error = ToolRegistry.errorPayload(e);
			return errorResult(error);
		} catch (RuntimeException e) {
			LOG.error("tool '{}' crashed", name, e);
			return errorResult(new ErrorPayload(ErrorCode.INTERNAL_ERROR,
					"internal error in tool '" + name + "'", null));
		}
	}

	private static McpSchema.CallToolResult errorResult(ErrorPayload error) {
		return McpSchema.CallToolResult.builder()
				.addTextContent(ToolRegistry.toJson(error))
				.structuredContent(error)
				.isError(true)
				.build();
	}
}
