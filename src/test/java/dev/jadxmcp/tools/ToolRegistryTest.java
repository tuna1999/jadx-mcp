package dev.jadxmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;

import dev.jadxmcp.core.IndexConfig;
import dev.jadxmcp.model.IndexState;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.fixture.FixtureApk;
import dev.jadxmcp.model.ApkInfo;
import dev.jadxmcp.model.ClassOutline;
import dev.jadxmcp.model.ClassSource;
import dev.jadxmcp.model.ErrorCode;
import dev.jadxmcp.model.ManifestInfo;
import dev.jadxmcp.model.MethodSource;
import dev.jadxmcp.model.XrefInfo;

/** Tools exercised directly through the registry, independent of any transport. */
class ToolRegistryTest {

	private JadxService jadx;
	private ToolRegistry registry;

	@TempDir
	Path indexDir;

	@BeforeAll
	static void apk() {
		FixtureApk.apk();
	}

	@AfterEach
	void tearDown() {
		if (jadx != null) {
			jadx.close();
		}
	}

	private ToolRegistry loaded() {
		jadx = new JadxService(IndexConfig.of(indexDir));
		jadx.load(FixtureApk.apk());
		registry = new ToolRegistry(jadx);
		return registry;
	}

	@Test
	void exposesExactlyPhaseOneTools() {
		registry = loaded();
		List<String> names = registry.definitions().stream().map(ToolDefinition::name).sorted().toList();
		assertEquals(List.of(
				"get_apk_info", "get_class_outline", "get_class_source", "get_manifest", "get_method_source",
				"get_resource", "get_xrefs", "list_classes", "list_packages", "list_resources", "load_apk",
				"search_classes", "search_methods", "search_strings"), names);
	}

	@Test
	void noApkLoadedErrorsAreStructured() {
		jadx = new JadxService(IndexConfig.of(indexDir));
		registry = new ToolRegistry(jadx);
		ToolException e = assertThrows(ToolException.class,
				() -> registry.call("list_classes", Map.of()));
		assertEquals(ErrorCode.NO_APK_LOADED, e.code());
	}

	@Test
	void loadApkReturnsMetadata() {
		registry = loaded();
		ApkInfo info = assertInstanceOf(ApkInfo.class,
				registry.call("load_apk", Map.of("path", FixtureApk.apk().toString())));
		assertTrue(info.loaded());
		assertTrue(info.classCount() >= 5);
		assertEquals("dev.jadxmcp.fixture", info.manifestPackage());
	}

	@Test
	void invalidArgumentsRejected() {
		registry = loaded();
		assertEquals(ErrorCode.INVALID_ARGUMENT,
				assertThrows(ToolException.class, () -> registry.call("load_apk", Map.of())).code());
		assertEquals(ErrorCode.INVALID_ARGUMENT,
				assertThrows(ToolException.class,
						() -> registry.call("search_classes", Map.of("limit", 5))).code()); // missing query
	}

	@Test
	void unknownClassNotFound() {
		registry = loaded();
		assertEquals(ErrorCode.CLASS_NOT_FOUND, assertThrows(ToolException.class,
				() -> registry.call("get_class_outline", Map.of("class", "Lno/such/Class;"))).code());
	}

	@Test
	void unknownMethodNotFound() {
		registry = loaded();
		assertEquals(ErrorCode.METHOD_NOT_FOUND, assertThrows(ToolException.class,
				() -> registry.call("get_method_source",
						Map.of("method", "Ldev/jadxmcp/fixture/CryptoUtil;->nope(I)I"))).code());
	}

	@Test
	void malformedMethodIdIsInvalidSymbol() {
		registry = loaded();
		assertEquals(ErrorCode.INVALID_SYMBOL_ID, assertThrows(ToolException.class,
				() -> registry.call("get_method_source", Map.of("method", "garbage"))).code());
	}

	@Test
	void listClassesPaginates() {
		registry = loaded();
		Map<?, ?> page1 = (Map<?, ?>) registry.call("list_classes",
				Map.of("offset", 0, "limit", 2));
		dev.jadxmcp.model.Page pageMeta = (dev.jadxmcp.model.Page) page1.get("page");
		assertEquals(0, pageMeta.offset());
		assertEquals(2, pageMeta.limit());
		assertTrue(pageMeta.total() > 2);
		assertEquals(true, pageMeta.hasMore());

		Map<?, ?> page2 = (Map<?, ?>) registry.call("list_classes",
				Map.of("offset", pageMeta.nextOffset(), "limit", 2));
		assertNotEqualItems(page1, page2);
	}

	@SuppressWarnings("unchecked")
	private static void assertNotEqualItems(Map<?, ?> a, Map<?, ?> b) {
		assertFalse(((List<Object>) a.get("items")).equals(b.get("items")), "pages must differ");
	}

	@Test
	void classOutlineHasStructure() {
		registry = loaded();
		ClassOutline outline = assertInstanceOf(ClassOutline.class,
				registry.call("get_class_outline", Map.of("class", "Ldev/jadxmcp/fixture/CryptoUtil;")));
		assertEquals("Ldev/jadxmcp/fixture/CryptoUtil;", outline.id());
		assertEquals("dev.jadxmcp.fixture.CryptoUtil", outline.name());
		assertTrue(outline.methods().stream().anyMatch(m -> m.name().equals("encode")));
		assertTrue(outline.fields().stream().anyMatch(f -> f.name().equals("ALGORITHM")));
	}

	@Test
	void classSourceTruncates() {
		registry = loaded();
		ClassSource src = assertInstanceOf(ClassSource.class,
				registry.call("get_class_source",
						Map.of("class", "Ldev/jadxmcp/fixture/BigTable;", "maxChars", 1000)));
		assertTrue(src.truncated());
		assertEquals(1000, src.returnedChars());
		assertTrue(src.totalChars() > 1000);
	}

	@Test
	void methodSourceReturnsMethodOnly() {
		registry = loaded();
		MethodSource src = assertInstanceOf(MethodSource.class,
				registry.call("get_method_source",
						Map.of("method", "Ldev/jadxmcp/fixture/DataStore;->fetch(Ljava/lang/String;)Ljava/lang/String;")));
		assertEquals("fetch", src.name());
		assertTrue(src.source().contains("encode"));
		assertTrue(src.source().contains(".length"));
		assertFalse(src.source().contains("class DataStore"));
	}

	@Test
	void searchToolsWork() {
		registry = loaded();
		Map<?, ?> classes = (Map<?, ?>) registry.call("search_classes", Map.of("query", "cryptoutil"));
		assertFalse(((List<?>) classes.get("items")).isEmpty());

		Map<?, ?> methods = (Map<?, ?>) registry.call("search_methods",
				Map.of("query", "encode", "class", "fixture"));
		assertFalse(((List<?>) methods.get("items")).isEmpty());

		Map<?, ?> strings = (Map<?, ?>) registry.call("search_strings",
				Map.of("query", "api.fixture.example.com"));
		assertFalse(((List<?>) strings.get("items")).isEmpty());
	}

	@Test
	void xrefToolReturnsIncomingUsages() {
		registry = loaded();
		XrefInfo xrefs = assertInstanceOf(XrefInfo.class,
				registry.call("get_xrefs", Map.of(
						"symbolType", "method",
						"id", "Ldev/jadxmcp/fixture/CryptoUtil;->encode([B[B)[B")));
		assertEquals("method", xrefs.symbol().type());
		assertTrue(xrefs.incomingCount() >= 1);
		assertTrue(xrefs.incoming().stream().anyMatch(r -> "fetch".equals(r.name())));
	}

	@Test
	void manifestToolDecodes() {
		registry = loaded();
		ManifestInfo manifest = assertInstanceOf(ManifestInfo.class, registry.call("get_manifest", Map.of()));
		assertTrue(manifest.present());
		assertEquals("dev.jadxmcp.fixture", manifest.packageName());
		assertEquals("1.0", manifest.versionName());
		assertEquals(1, manifest.versionCode());
	}

	@Test
	void resourceToolsWork() {
		registry = loaded();
		Map<?, ?> list = (Map<?, ?>) registry.call("list_resources", Map.of("query", "notes"));
		assertEquals(1, ((List<?>) list.get("items")).size());

		dev.jadxmcp.model.ResourceContent content = assertInstanceOf(dev.jadxmcp.model.ResourceContent.class,
				registry.call("get_resource", Map.of("path", "assets/notes.txt")));
		assertEquals("text", content.kind());
		assertTrue(content.text().contains("fixture notes"));

		assertEquals(ErrorCode.RESOURCE_NOT_FOUND, assertThrows(ToolException.class,
				() -> registry.call("get_resource", Map.of("path", "no/such/file"))).code());
	}

	@Test
	void indexLifecycleUpgradesTools() throws Exception {
		registry = loaded();
		// build is synchronous: the index must already be ready
		ApkInfo info = assertInstanceOf(ApkInfo.class, registry.call("get_apk_info", Map.of()));
		assertEquals(IndexState.READY, info.index().state());

		Map<?, ?> strings = (Map<?, ?>) registry.call("search_strings",
				Map.of("query", "api-client-ready"));
		dev.jadxmcp.model.StringMatch match = assertInstanceOf(dev.jadxmcp.model.StringMatch.class,
				((List<?>) strings.get("items")).get(0));
		assertNotNull(match.methodId());

		dev.jadxmcp.model.XrefInfo xref = assertInstanceOf(dev.jadxmcp.model.XrefInfo.class,
				registry.call("get_xrefs", Map.of("symbolType", "method", "id", FixtureApk.API_CLIENT_CALL_ID)));
		assertNotNull(xref.outgoing());
		assertTrue(xref.outgoingCount() >= 1);

		ApkInfo ready = assertInstanceOf(ApkInfo.class, registry.call("get_apk_info", Map.of()));
		assertTrue(ready.index().stringCount() > 0);
		assertTrue(ready.index().edgeCount() > 0);
	}

	@Test
	void disabledIndexKeepsPhase1Behavior() throws Exception {
		jadx = new JadxService(IndexConfig.disabled());
		jadx.load(FixtureApk.apk());
		registry = new ToolRegistry(jadx);
		ApkInfo info = assertInstanceOf(ApkInfo.class, registry.call("get_apk_info", Map.of()));
		assertEquals(IndexState.DISABLED, info.index().state());
		Map<?, ?> strings = (Map<?, ?>) registry.call("search_strings",
				Map.of("query", "api-client-ready"));
		dev.jadxmcp.model.StringMatch match = assertInstanceOf(dev.jadxmcp.model.StringMatch.class,
				((List<?>) strings.get("items")).get(0));
		assertNotNull(match.line()); // Phase 1 decompiled-scan shape
		assertNull(match.methodId());
	}

	@Test
	void resourceByNegativeIdBehaves() {
		registry = loaded();
		// fixture APK has no resources.arsc: any id must be RESOURCE_NOT_FOUND
		assertEquals(ErrorCode.RESOURCE_NOT_FOUND, assertThrows(ToolException.class,
				() -> registry.call("get_resource", Map.of("id", "0x7f0e0001"))).code());
		assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(ToolException.class,
				() -> registry.call("get_resource", Map.of("maxBytes", 2000))).code());
		assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(ToolException.class,
				() -> registry.call("get_resource", Map.of("path", "assets/notes.txt", "id", "0x1"))).code());
		// entries carry no id when no resource table exists
		Map<?, ?> list = (Map<?, ?>) registry.call("list_resources", Map.of("query", "notes"));
		dev.jadxmcp.model.ResourceEntryInfo item = assertInstanceOf(dev.jadxmcp.model.ResourceEntryInfo.class,
				((List<?>) list.get("items")).get(0));
		assertNull(item.id());
	}
}
