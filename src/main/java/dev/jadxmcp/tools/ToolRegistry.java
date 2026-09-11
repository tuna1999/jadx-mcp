package dev.jadxmcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.model.ErrorPayload;
import dev.jadxmcp.model.ErrorCode;
import dev.jadxmcp.util.Json;

/**
 * The single source of truth for the MCP tool set. Both STDIO and HTTP
 * transports consume this registry; it contains all business logic and no
 * transport code.
 */
public final class ToolRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

	private final Map<String, ToolDefinition> tools;
	private final java.util.concurrent.atomic.AtomicLong inFlight = new java.util.concurrent.atomic.AtomicLong();

	public ToolRegistry(JadxService jadx) {
		List<ToolDefinition> all = new ArrayList<>();
		all.addAll(new ApkTools(jadx).tools());
		all.addAll(new ClassTools(jadx).tools());
		all.addAll(new MethodTools(jadx).tools());
		all.addAll(new SearchTools(jadx).tools());
		all.addAll(new XrefTools(jadx).tools());
		all.addAll(new ResourceTools(jadx).tools());
		Map<String, ToolDefinition> map = new LinkedHashMap<>();
		for (ToolDefinition t : all) {
			if (map.putIfAbsent(t.name(), t) != null) {
				throw new IllegalStateException("duplicate tool name: " + t.name());
			}
		}
		this.tools = Map.copyOf(map);
	}

	public List<ToolDefinition> definitions() {
		return List.copyOf(tools.values());
	}

	public ToolDefinition definition(String name) {
		return tools.get(name);
	}

	/**
	 * Invoke a tool by name. Returns the success payload. Tool failures are
	 * reported via {@link ToolException}; transport layers convert both into MCP
	 * {@code CallToolResult}s.
	 */
	public Object call(String name, Map<String, Object> arguments) {
		ToolDefinition tool = tools.get(name);
		if (tool == null) {
			throw new ToolException(ErrorCode.INVALID_ARGUMENT, "unknown tool: " + name);
		}
		inFlight.incrementAndGet();
		try {
			try {
				return tool.handler().apply(arguments == null ? Map.of() : arguments);
			} catch (ToolException e) {
				throw e;
			} catch (JadxService.JadxServiceException e) {
				throw new ToolException(e.code(), e.getMessage(),
						e.getCause() == null ? null : String.valueOf(e.getCause().getMessage()));
			} catch (IllegalArgumentException e) {
				throw new ToolException(ErrorCode.INVALID_ARGUMENT, e.getMessage());
			}
		} finally {
			inFlight.decrementAndGet();
		}
	}

	/**
	 * Wait until no tool calls are executing (used for graceful shutdown after
	 * stdin EOF). Returns false on timeout.
	 */
	public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		return inFlight.get() == 0;
	}

	/** Serialize any payload (record or map) to JSON text for TextContent. */
	public static String toJson(Object payload) {
		return Json.write(payload);
	}

	public static ErrorPayload errorPayload(ToolException e) {
		return new ErrorPayload(e.code(), e.getMessage(), e.detail());
	}

}
