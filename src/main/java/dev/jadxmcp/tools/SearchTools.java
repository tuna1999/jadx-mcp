package dev.jadxmcp.tools;

import java.util.List;
import java.util.Map;

import dev.jadxmcp.core.ApkSession;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.model.Page;

/** search_classes, search_methods, search_strings. */
public final class SearchTools {

	private final JadxService jadx;

	public SearchTools(JadxService jadx) {
		this.jadx = jadx;
	}

	public List<ToolDefinition> tools() {
		return List.of(
				searchClasses(),
				searchMethods(),
				searchStrings());
	}

	private ToolDefinition searchClasses() {
		return new ToolDefinition(
				"search_classes",
				"Search class names and package names (substring, case-insensitive). Returns stable class ids.",
				"""
						{
						  "type": "object",
						  "properties": {
						    "query": { "type": "string", "description": "Substring to match against class/package names" },
						    "limit": { "type": "integer", "minimum": 1, "maximum": 1000, "default": 100 }
						  },
						  "required": ["query"]
						}
						""",
				args -> {
					Args a = Args.of(args);
					String query = a.str("query");
					int limit = a.intOf("limit", 100, 1, 1000);
					ApkSession session = jadx.requireSession();
					var items = session.search().searchClasses(query, limit);
					return Map.of("items", items, "page", Page.of(0, limit, items.size()));
				});
	}

	private ToolDefinition searchMethods() {
		return new ToolDefinition(
				"search_methods",
				"Search method names and signatures (substring, case-insensitive), optionally filtered by class/package name.",
				"""
						{
						  "type": "object",
						  "properties": {
						    "query": { "type": "string", "description": "Substring to match against method name or signature" },
						    "class": { "type": "string", "description": "Optional class/package name substring filter" },
						    "limit": { "type": "integer", "minimum": 1, "maximum": 1000, "default": 100 }
						  },
						  "required": ["query"]
						}
						""",
				args -> {
					Args a = Args.of(args);
					String query = a.str("query");
					String classFilter = a.optStr("class");
					int limit = a.intOf("limit", 100, 1, 1000);
					ApkSession session = jadx.requireSession();
					var items = session.search().searchMethods(query, classFilter, limit);
					return Map.of("items", items, "page", Page.of(0, limit, items.size()));
				});
	}

	private ToolDefinition searchStrings() {
		return new ToolDefinition(
				"search_strings",
				"Search string constants across the whole input. When the Phase-2 index is ready "
						+ "this runs on pre-extracted dex string constants (no decompilation) and results carry "
						+ "method context (methodId/methodName). Otherwise it falls back to scanning decompiled "
						+ "sources with line numbers. 'maxClasses' is deprecated and ignored.",
				"""
						{
						  "type": "object",
						  "properties": {
						    "query": { "type": "string", "description": "Substring to match against string constants" },
						    "maxClasses": { "type": "integer", "minimum": 1, "maximum": 100000, "default": 5000,
						      "description": "Deprecated: ignored. Kept for backward compatibility." }
						  },
						  "required": ["query"]
						}
						""",
				args -> {
					Args a = Args.of(args);
					String query = a.str("query");
					boolean caseSensitive = a.boolOf("caseSensitive", false);
					int limit = a.intOf("limit", 50, 1, 500);
					int maxClasses = a.intOf("maxClasses", 5000, 1, 100_000);
					ApkSession session = jadx.requireSession();
					var items = session.search().searchStrings(query, caseSensitive, limit, maxClasses);
					return Map.of(
							"items", items,
							"page", Page.of(0, limit, items.size()),
							"classesDecompiled", session.codeCache().decompiledCount());
				});
	}
}
