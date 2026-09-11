package dev.jadxmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jadxmcp.fixture.FixtureApk;
import dev.jadxmcp.model.StringMatch;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;

class IndexedSearchServiceTest {

	@TempDir
	Path tmp;

	private JadxDecompiler jadx;
	private CodeCache codeCache;

	@BeforeEach
	void load() throws Exception {
		JadxArgs args = new JadxArgs();
		args.setInputFile(FixtureApk.apk().toFile());
		jadx = new JadxDecompiler(args);
		jadx.load();
		codeCache = new JadxCodeCache(jadx);
	}

	@AfterEach
	void unload() {
		if (jadx != null) {
			jadx.close();
		}
	}

	@Test
	void indexedSearchCarriesMethodContext() throws Exception {
		IndexStore store = IndexStore.open(tmp, "s1", JadxDecompiler.getVersion());
		assertNotNull(store);
		IndexBuilder.build(jadx.getRoot(), store);
		store.markReady();

		IndexedSearchService search = new IndexedSearchService(
				new JadxSearchService(jadx.getRoot(), codeCache), store);
		var matches = search.searchStrings("api-client-ready", false, 10, 1);
		assertEquals(1, matches.size());
		StringMatch m = matches.get(0);
		assertEquals(FixtureApk.API_CLIENT_DESCRIBE, m.value());
		assertNotNull(m.methodId());
		assertNotNull(m.methodName());
		assertNull(m.line());
		store.close();
	}

	@Test
	void delegatesToPhase1WhileBuilding() throws Exception {
		IndexStore store = IndexStore.open(tmp, "s2", JadxDecompiler.getVersion());
		assertNotNull(store); // still BUILDING: no rows, not ready

		IndexedSearchService search = new IndexedSearchService(
				new JadxSearchService(jadx.getRoot(), codeCache), store);
		var matches = search.searchStrings("api-client-ready", false, 10, 100);
		assertEquals(1, matches.size());
		assertNotNull(matches.get(0).line()); // Phase-1 shape: decompiled-scan line number
		assertNull(matches.get(0).methodId());
		store.close();
	}

	@Test
	void classAndMethodSearchDelegateUnchanged() throws Exception {
		IndexStore store = IndexStore.open(tmp, "s3", JadxDecompiler.getVersion());
		assertNotNull(store);
		IndexedSearchService search = new IndexedSearchService(
				new JadxSearchService(jadx.getRoot(), codeCache), store);
		assertTrue(search.searchClasses("ApiClient", 10).size() == 1);
		assertTrue(search.searchMethods("encode", null, 10).size() >= 1);
		store.close();
	}
}
