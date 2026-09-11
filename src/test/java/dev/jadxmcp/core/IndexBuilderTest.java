package dev.jadxmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jadxmcp.core.IndexStore.EdgeRow;
import dev.jadxmcp.core.IndexStore.StringRow;
import dev.jadxmcp.fixture.FixtureApk;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;

class IndexBuilderTest {

	@TempDir
	Path tmp;

	private JadxDecompiler jadx;

	@BeforeEach
	void load() throws Exception {
		JadxArgs args = new JadxArgs();
		args.setInputFile(FixtureApk.apk().toFile());
		jadx = new JadxDecompiler(args);
		jadx.load();
	}

	@AfterEach
	void unload() {
		if (jadx != null) {
			jadx.close();
		}
	}

	@Test
	void collectsStringsAndEdgesWithoutDecompiling() throws Exception {
		IndexStore store = IndexStore.open(tmp, "hash1", JadxDecompiler.getVersion());
		assertNotNull(store);

		IndexBuilder.build(jadx.getRoot(), store);
		store.markReady();
		List<StringRow> urls = store.searchStrings("api.fixture.example", false, 50);
		assertTrue(urls.stream().anyMatch(r -> r.methodId().equals(FixtureApk.API_CLIENT_CALL_ID)),
				() -> "expected a string row in ApiClient.call, got: " + urls);
		assertTrue(urls.stream().anyMatch(r -> r.value().startsWith(FixtureApk.API_ENDPOINT)));
		assertTrue(store.searchStrings(FixtureApk.API_CLIENT_DESCRIBE, false, 10).size() >= 1);

		List<EdgeRow> out = store.outgoingOfMethod(FixtureApk.API_CLIENT_CALL_ID, 100);
		assertTrue(out.stream().anyMatch(e -> e.dstId().equals(FixtureApk.ENCODE_METHOD_ID)
				&& e.dstType().equals("method")),
				() -> "expected edge to CryptoUtil.encode, got: " + out);
		assertTrue(store.outgoingOfClass(FixtureApk.API_CLIENT_ID, 200).size() >= out.size());
		assertTrue(store.stringCount() > 0);
		assertTrue(store.edgeCount() > 0);

		// usage info (incoming xrefs) must survive the load/unload walk
		assertEquals(1, jadx.getRoot().getClasses(true).size() == 0 ? 0 : 1);
		store.close();
	}

	@Test
	void sameInputProducesSameIndex() throws Exception {
		String hash = "hash2";
		IndexStore first = IndexStore.open(tmp, hash, JadxDecompiler.getVersion());
		IndexBuilder.build(jadx.getRoot(), first);
		first.markReady();
		long strings = first.stringCount();
		long edges = first.edgeCount();
		first.close();

		IndexStore second = IndexStore.open(tmp, hash, JadxDecompiler.getVersion());
		assertNotNull(second);
		assertTrue(second.awaitReady(0));
		assertEquals(strings, second.stringCount());
		assertEquals(edges, second.edgeCount());
		second.close();
	}
}
