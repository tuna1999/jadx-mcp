package dev.jadxmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jadxmcp.fixture.FixtureApk;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

class IndexedCodeCacheTest {

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

	private ClassNode cryptoClass() {
		RootNode root = jadx.getRoot();
		return root.getClasses(true).stream()
				.filter(c -> c.getRawName().equals("dev.jadxmcp.fixture.CryptoUtil"))
				.findFirst()
				.orElseThrow();
	}

	@Test
	void sourcePersistsAcrossStoreReopen() throws Exception {
		IndexStore store = IndexStore.open(tmp, "c1", JadxDecompiler.getVersion());
		assertNotNull(store);

		String first = new IndexedCodeCache(new JadxCodeCache(jadx), store).getClassSource(cryptoClass());
		assertNotNull(first);
		assertTrue(first.contains("XOR-CIPHER-V1"));
		store.markReady(); // finalize tmp db into place before reopen
		store.close();

		IndexStore reopened = IndexStore.open(tmp, "c1", JadxDecompiler.getVersion());
		assertNotNull(reopened);
		assertTrue(reopened.awaitReady(0));
		JadxCodeCache freshDelegate = new JadxCodeCache(jadx);
		String second = new IndexedCodeCache(freshDelegate, reopened).getClassSource(cryptoClass());
		assertEquals(first, second);
		assertEquals(0, freshDelegate.decompiledCount()); // served from disk, not decompiled
		reopened.close();
	}
}
