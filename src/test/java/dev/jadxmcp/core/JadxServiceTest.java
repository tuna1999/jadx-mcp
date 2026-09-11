package dev.jadxmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.jadxmcp.fixture.FixtureApk;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;

class JadxServiceTest {

	private JadxService service;

	@BeforeAll
	static void apk() {
		FixtureApk.apk();
	}

	@AfterEach
	void tearDown() {
		if (service != null) {
			service.close();
		}
	}

	private ApkSession load() {
		service = new JadxService();
		return service.load(FixtureApk.apk());
	}

	@Test
	void apkLoadsSuccessfully() {
		ApkSession session = load();
		assertTrue(session.topLevelClassCount() >= 5, "fixture should contain at least 5 top-level classes");
		assertTrue(session.resources().size() >= 3);
		assertTrue(session.manifestResource().isPresent());
	}

	@Test
	void missingFileFailsCleanly() {
		service = new JadxService();
		JadxService.JadxServiceException e = assertThrows(JadxService.JadxServiceException.class,
				() -> service.load(Path.of("does/not/exist.apk")));
		assertEquals(dev.jadxmcp.model.ErrorCode.FILE_NOT_FOUND, e.code());
	}

	@Test
	void invalidFileFailsCleanly() throws Exception {
		Path notAnApk = Files.createTempFile("jadx-mcp", ".apk");
		Files.writeString(notAnApk, "this is definitely not a zip file");
		service = new JadxService();
		JadxService.JadxServiceException e = assertThrows(JadxService.JadxServiceException.class,
				() -> service.load(notAnApk));
		assertEquals(dev.jadxmcp.model.ErrorCode.INVALID_INPUT_FILE, e.code());
	}

	@Test
	void listClassesWorks() {
		ApkSession session = load();
		List<ClassNode> classes = session.root().getClasses(false);
		assertTrue(classes.stream().anyMatch(c -> c.getRawName().equals("dev.jadxmcp.fixture.CryptoUtil")));
		assertTrue(classes.stream().anyMatch(c -> c.getRawName().equals("dev.jadxmcp.fixture.Outer")));
	}

	@Test
	void classLookupWorks() {
		ApkSession session = load();
		SymbolResolver resolver = session.resolver();
		assertNotNull(resolver.resolveClass("Ldev/jadxmcp/fixture/CryptoUtil;"), "dex id lookup");
		assertNotNull(resolver.resolveClass("dev.jadxmcp.fixture.CryptoUtil"), "dotted lookup");
		assertNotNull(resolver.resolveClass("dev.jadxmcp.fixture.Outer$Inner"), "inner class lookup");
		assertEquals("Ldev/jadxmcp/fixture/CryptoUtil;",
				SymbolResolver.classId(resolver.resolveClass("Ldev/jadxmcp/fixture/CryptoUtil;")));
	}

	@Test
	void methodLookupWorks() {
		ApkSession session = load();
		MethodNode mth = session.resolver().resolveMethod(FixtureApk.ENCODE_METHOD_ID);
		assertNotNull(mth, "method by dex id");
		assertEquals("encode", mth.getMethodInfo().getName());
		assertEquals(FixtureApk.ENCODE_METHOD_ID, SymbolResolver.methodId(mth));
	}

	@Test
	void methodSourceDecompiles() {
		ApkSession session = load();
		MethodNode mth = session.resolver().resolveMethod(FixtureApk.ENCODE_METHOD_ID);
		String classSource = session.codeCache().getClassSource(mth.getParentClass());
		assertTrue(classSource.contains("class CryptoUtil"));
		assertTrue(classSource.contains("XOR-CIPHER-V1"));
		String methodSource = mth.getCodeStr();
		assertNotNull(methodSource);
		assertTrue(methodSource.contains(".length"));
	}

	@Test
	void manifestRetrievalWorks() {
		ApkSession session = load();
		String xml = session.manifestResource().get().loadContent().getText().getCodeStr();
		assertNotNull(xml);
		assertTrue(xml.contains("package=\"dev.jadxmcp.fixture\""), "manifest package: " + xml);
		assertTrue(xml.contains("android.permission.INTERNET"));
	}

	@Test
	void xrefsWork() {
		ApkSession session = load();
		MethodNode encode = session.resolver().resolveMethod(FixtureApk.ENCODE_METHOD_ID);
		List<MethodNode> useIn = encode.getUseIn();
		assertTrue(useIn.stream().anyMatch(m -> m.getMethodInfo().getName().equals("fetch")),
				"DataStore.fetch should call CryptoUtil.encode, got: " + useIn);

		ClassNode cryptoUtil = session.resolver().resolveClass(FixtureApk.CRYPTO_UTIL_ID);
		assertFalse(cryptoUtil.getUseIn().isEmpty(), "CryptoUtil should have incoming class refs");
	}

	@Test
	void searchStringsFindsConstants() {
		ApkSession session = load();
		List<dev.jadxmcp.model.StringMatch> matches =
				session.search().searchStrings("api.fixture.example.com", false, 20, 100);
		assertTrue(matches.size() >= 1);
		assertTrue(matches.get(0).className().contains("DataStore"));
	}

	@Test
	void resourceContentWorks() throws Exception {
		ApkSession session = load();
		var notes = session.resources().stream()
				.filter(r -> r.getOriginalName().equals("assets/notes.txt"))
				.findFirst();
		assertTrue(notes.isPresent());
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(session.inputPath().toFile())) {
			var entry = zip.getEntry("assets/notes.txt");
			assertNotNull(entry);
			String text = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			assertTrue(text.contains("fixture notes"));
		}
	}

	@Test
	void replacingInputUnloadsPrevious() {
		ApkSession first = load();
		ApkSession second = service.load(FixtureApk.apk());
		assertTrue(service.session().isPresent());
		assertEquals(second, service.session().get());
		// first session must be closed and no longer the active one
		assertFalse(first == service.session().get());
	}
}
