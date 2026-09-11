package dev.jadxmcp.core;

import java.util.ArrayList;
import java.util.List;

import dev.jadxmcp.model.ClassEntry;
import dev.jadxmcp.model.MethodEntry;
import dev.jadxmcp.model.StringMatch;

/**
 * {@link SearchService} backed by the Phase 2 {@link IndexStore} when the
 * index is ready; delegates to the Phase 1 implementation otherwise (or for
 * class/method search, which never needs the index — it scans loaded jadx
 * nodes without decompiling).
 */
public final class IndexedSearchService implements SearchService {

	private final SearchService fallback;
	private final IndexStore store;

	public IndexedSearchService(SearchService fallback, IndexStore store) {
		this.fallback = fallback;
		this.store = store;
	}

	@Override
	public List<ClassEntry> searchClasses(String query, int limit) {
		return fallback.searchClasses(query, limit);
	}

	@Override
	public List<MethodEntry> searchMethods(String query, String classFilter, int limit) {
		return fallback.searchMethods(query, classFilter, limit);
	}

	@Override
	public List<StringMatch> searchStrings(String query, boolean caseSensitive, int limit, int maxClassesToScan) {
		if (!store.ready()) {
			return fallback.searchStrings(query, caseSensitive, limit, maxClassesToScan);
		}
		List<StringMatch> result = new ArrayList<>();
		for (IndexStore.StringRow row : store.searchStrings(query, caseSensitive, limit)) {
			result.add(new StringMatch(row.value(), row.classId(), row.className(), null,
					row.methodId(), row.methodName()));
			if (result.size() >= limit) {
				break;
			}
		}
		return result;
	}
}
