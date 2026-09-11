package dev.jadxmcp.core;

import java.util.List;

import dev.jadxmcp.model.ClassEntry;
import dev.jadxmcp.model.MethodEntry;
import dev.jadxmcp.model.StringMatch;

/**
 * Search over the loaded input. Phase 1 scans loaded jadx structures; Phase 2
 * can replace this with an FTS5/SQLite backed index without changing the MCP
 * tool API.
 */
public interface SearchService {

	List<ClassEntry> searchClasses(String query, int limit);

	/**
	 * @param query       substring of method name or signature
	 * @param classFilter optional class/package name substring filter
	 */
	List<MethodEntry> searchMethods(String query, String classFilter, int limit);

	/**
	 * Search string constants in decompiled sources. Decompilation happens lazily
	 * once per session and is cached.
	 */
	List<StringMatch> searchStrings(String query, boolean caseSensitive, int limit, int maxClassesToScan);
}
