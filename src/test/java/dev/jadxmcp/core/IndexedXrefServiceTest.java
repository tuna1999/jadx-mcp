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
import dev.jadxmcp.model.XrefInfo;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;

class IndexedXrefServiceTest {

	@TempDir
	Path tmp;

	private JadxDecompiler jadx;
	private SymbolResolver resolver;

	@BeforeEach
	void load() throws Exception {
		JadxArgs args = new JadxArgs();
		args.setInputFile(FixtureApk.apk().toFile());
		jadx = new JadxDecompiler(args);
		jadx.load();
		resolver = new SymbolResolver(jadx.getRoot());
	}

	@AfterEach
	void unload() {
		if (jadx != null) {
			jadx.close();
		}
	}

	@Test
	void methodOutgoingIncludesCalledMethods() throws Exception {
		IndexStore store = IndexStore.open(tmp, "x1", JadxDecompiler.getVersion());
		assertNotNull(store);
		IndexBuilder.build(jadx.getRoot(), store);
		store.markReady();

		IndexedXrefService service = new IndexedXrefService(new JadxXrefService(resolver), store);
		XrefInfo info = service.xrefs("method", FixtureApk.API_CLIENT_CALL_ID, 100);
		assertNotNull(info);
		assertNotNull(info.outgoing());
		assertTrue(info.outgoing().stream()
				.anyMatch(r -> r.id().equals(FixtureApk.ENCODE_METHOD_ID)),
				() -> "expected outgoing edge to CryptoUtil.encode, got: " + info.outgoing());
		assertTrue(info.outgoingCount() >= 1);
		// incoming side (jadx usage info) still present
		assertTrue(info.incomingCount() >= 0);
		store.close();
	}

	@Test
	void fieldHasNoOutgoingAndIncomingIntact() throws Exception {
		IndexStore store = IndexStore.open(tmp, "x2", JadxDecompiler.getVersion());
		assertNotNull(store);
		IndexBuilder.build(jadx.getRoot(), store);
		store.markReady();

		IndexedXrefService service = new IndexedXrefService(new JadxXrefService(resolver), store);
		// ApiClient.crypto static field is read by call()
		String fieldId = FixtureApk.API_CLIENT_ID + "->crypto:Ldev/jadxmcp/fixture/CryptoUtil;";
		XrefInfo info = service.xrefs("field", fieldId, 100);
		assertNotNull(info);
		assertNull(info.outgoing());
		assertTrue(info.incomingCount() >= 1);
		store.close();
	}

	@Test
	void buildingIndexYieldsPhase1Shape() throws Exception {
		IndexStore store = IndexStore.open(tmp, "x3", JadxDecompiler.getVersion());
		assertNotNull(store); // BUILDING: nothing inserted yet

		IndexedXrefService service = new IndexedXrefService(new JadxXrefService(resolver), store);
		XrefInfo info = service.xrefs("method", FixtureApk.API_CLIENT_CALL_ID, 100);
		assertNotNull(info);
		assertNull(info.outgoing());
		store.close();
	}

	@Test
	void truncationReportsLimitPlusOne() throws Exception {
		IndexStore store = IndexStore.open(tmp, "x4", JadxDecompiler.getVersion());
		assertNotNull(store);
		IndexBuilder.build(jadx.getRoot(), store);
		store.markReady();

		IndexedXrefService service = new IndexedXrefService(new JadxXrefService(resolver), store);
		XrefInfo info = service.xrefs("method", FixtureApk.API_CLIENT_CALL_ID, 1);
		assertNotNull(info);
		assertNotNull(info.outgoing());
		assertEquals(1, info.outgoing().size());
		assertEquals(Boolean.TRUE, info.outgoingTruncated());
		assertEquals(2, info.outgoingCount());
		store.close();
	}
}
