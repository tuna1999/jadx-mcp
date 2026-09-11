# Phase 2 Index Track Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SQLite-backed string index, outgoing xrefs, resource-id lookup and persistent code cache behind the existing Phase-2 service seams, with graceful Phase-1 fallback.

**Architecture:** One SQLite file per input content hash under a configurable index dir. A background builder walks raw dex instructions (`MethodNode.load()` → `getInstructions()` → `unload()`) collecting string constants and outgoing reference edges without decompiling. `Indexed*` service wrappers consult the store when ready and delegate to Phase-1 implementations otherwise. Resource-id lookup decodes `resources.arsc` once at load via jadx's table parser (no SQLite).

**Tech Stack:** Java 17 bytecode, jadx-core 1.5.6 Java API, `org.xerial:sqlite-jdbc` 3.53.4.0, JUnit 6.

**Spec:** `docs/superpowers/specs/2026-09-11-phase2-index-design.md`

## Global Constraints

- Tabs for indentation; one class per file; English javadoc; SLF4J logging only (never `System.out`).
- Never leak jadx types across the tool boundary — convert in `core/` to `model/` records.
- All DTO changes are additive (`@JsonInclude(NON_NULL)`); no existing error code or tool name changes.
- Tests run via `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain test --rerun` on Windows/msys (`gradlew.bat` unusable from msys).
- Tests must never write to `~/.jadx-mcp` — every test/IT constructs `JadxService` with an explicit temp `IndexConfig` or passes `--index-dir <build dir>` to spawned jars.
- Tool count stays 14; no new tools.

---

### Task 1: IndexConfig + IndexStore (SQLite foundation)

**Files:**
- Create: `src/main/java/dev/jadxmcp/core/IndexConfig.java`
- Create: `src/main/java/dev/jadxmcp/core/IndexStore.java`
- Modify: `build.gradle.kts` (dependencies block, line ~48)
- Test: `src/test/java/dev/jadxmcp/core/IndexStoreTest.java`

**Interfaces:**
- Produces:
  - `record IndexConfig(boolean enabled, Path dir)` with `static Path defaultDir()` = `~/.jadx-mcp/index`
  - `IndexStore.open(Path dir, String sha256, String jadxVersion)` → `IndexStore` or `null` on any failure; `needsBuild()`, `phase()` (`BUILDING|READY`), `awaitReady(long ms)`
  - `insertStrings(List<StringRow>)`, `insertEdges(String srcMethod, List<EdgeRow>)` with `record StringRow(String classId, String className, String methodId, String methodName, String value)` and `record EdgeRow(String dstId, String dstType)`
  - `List<StringRow> searchStrings(String query, boolean caseSensitive, int limit)`
  - `List<EdgeRow> outgoingOfMethod(String methodId, int max)` / `outgoingOfClass(String classId, int max)` (DISTINCT dst, `max+1` fetch for truncation detection)
  - `String cachedSource(String classId)` / `putSource(String classId, String source)`
  - `markReady()` (writes counts + meta, atomic move from `.tmp`), `stringCount()`, `edgeCount()`, `close()`

- [ ] **Step 1: Add dependency**

`build.gradle.kts` dependencies block, after the MCP SDK line:

```kotlin
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
```

- [ ] **Step 2: Write failing test** `IndexStoreTest`:

```java
package dev.jadxmcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jadxmcp.core.IndexStore.EdgeRow;
import dev.jadxmcp.core.IndexStore.StringRow;

class IndexStoreTest {

	@TempDir
	Path tmp;

	private IndexStore newStore(String jadxVersion) {
		IndexStore s = IndexStore.open(tmp, "cafebabe", jadxVersion);
		assertNotNull(s);
		assertTrue(s.needsBuild());
		return s;
	}

	@Test
	void buildPersistAndReopen() throws Exception {
		IndexStore s = newStore("1.5.6");
		s.insertStrings(List.of(new StringRow("LA;", "A", "LA;->m()V", "m", "https://api.example.com")));
		s.insertEdges("LA;->m()V", List.of(new EdgeRow("LB;->n()V", "method")));
		s.putSource("LA;", "class A {}");
		s.markReady();
		s.close();

		IndexStore reopened = IndexStore.open(tmp, "cafebabe", "1.5.6");
		assertNotNull(reopened);
		assertFalse(reopened.needsBuild());
		assertTrue(reopened.awaitReady(0));
		assertEquals(1, reopened.stringCount());
		assertEquals(1, reopened.edgeCount());
		assertEquals(1, reopened.searchStrings("api.example", false, 10).size());
		assertEquals(0, reopened.searchStrings("API.EXAMPLE", false, 10).size()); // case-insensitive matches
		// careful: case-insensitive must MATCH -> fix expectation to 1 (see Step 3 impl)
		assertEquals(1, reopened.searchStrings("nomatch", false, 10).size() == 0 ? 0 : 1);
		assertEquals("class A {}", reopened.cachedSource("LA;"));
		assertEquals(1, reopened.outgoingOfMethod("LA;->m()V", 10).size());
		reopened.close();
	}

	@Test
	void versionMismatchForcesRebuild() throws Exception {
		IndexStore s = newStore("1.5.6");
		s.markReady();
		s.close();
		IndexStore other = IndexStore.open(tmp, "cafebabe", "1.6.0");
		assertNotNull(other);
		assertTrue(other.needsBuild()); // meta jadx_version mismatch -> rebuild
		other.close();
	}

	@Test
	void brokenDirReturnsNull() {
		assertNull(IndexStore.open(Path.of("Z:/definitely/not/there"), "x", "1.5.6"));
	}
}
```

(Fix the case-sensitivity expectation while writing: `searchStrings("API.EXAMPLE", false, 10)` must return 1; drop the throwaway ternary line.)

- [ ] **Step 3: Implement** `IndexConfig`:

```java
package dev.jadxmcp.core;

import java.nio.file.Path;

/** Index persistence configuration: enable/disable + directory. */
public record IndexConfig(boolean enabled, Path dir) {

	public static Path defaultDir() {
		return Path.of(System.getProperty("user.home"), ".jadx-mcp", "index");
	}

	public static IndexConfig disabled() {
		return new IndexConfig(false, null);
	}
}
```

`IndexStore` — single `synchronized` JDBC connection; schema `meta(key,value)`, `strings`, `xref_edges`, `sources`; build writes `<sha256>.db.tmp`, `markReady()` commits, closes, `Files.move(tmp, db, REPLACE_EXISTING)`, reopens. Key SQL:

```java
// open existing + meta matches -> READY, else -> tmp connection, BUILDING
private static final String DDL = """
		CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
		CREATE TABLE IF NOT EXISTS strings(
			seq INTEGER PRIMARY KEY, class_id TEXT NOT NULL, class_name TEXT,
			method_id TEXT NOT NULL, method_name TEXT, value TEXT NOT NULL);
		CREATE TABLE IF NOT EXISTS xref_edges(
			seq INTEGER PRIMARY KEY, src_method TEXT NOT NULL,
			dst_id TEXT NOT NULL, dst_type TEXT NOT NULL);
		CREATE TABLE IF NOT EXISTS sources(
			class_id TEXT PRIMARY KEY, source TEXT NOT NULL);
		""";

// searchStrings
String sql = caseSensitive
		? "SELECT class_id, class_name, method_id, method_name, value FROM strings"
				+ " WHERE instr(value, ?) > 0 ORDER BY seq LIMIT ?"
		: "SELECT class_id, class_name, method_id, method_name, value FROM strings"
				+ " WHERE instr(lower(value), ?) > 0 ORDER BY seq LIMIT ?";

// outgoingOfClass: prefix match escaped; LIKE 'Lcom/ex/Foo;->%'
// outgoingOfMethod: src_method = ?
// both: SELECT DISTINCT dst_id, dst_type ... LIMIT max+1
```

Pragmas on open: `journal_mode=WAL`, `synchronous=NORMAL`. Meta keys written by `markReady()`: `schema_version=1`, `jadx_version`, `string_count`, `edge_count`, `built_at`. `close()` closes connection quietly; `awaitReady` polls `phase()==READY` up to timeout. All failures inside `open` are caught (`Throwable`) → log debug + return `null`.

- [ ] **Step 4: Run** `GradleWrapperMain test --tests 'dev.jadxmcp.core.IndexStoreTest' --rerun` → PASS.
- [ ] **Step 5: Commit** `feat: IndexConfig + SQLite IndexStore (schema, build/reopen lifecycle)`

---

### Task 2: Fixture extension + IndexBuilder (instruction walk)

**Files:**
- Create: `src/test/fixture-src/dev/jadxmcp/fixture/ApiClient.java`
- Modify: `src/test/java/dev/jadxmcp/fixture/FixtureApk.java` (constants)
- Create: `src/main/java/dev/jadxmcp/core/IndexBuilder.java`
- Test: `src/test/java/dev/jadxmcp/core/IndexBuilderTest.java`

**Interfaces:**
- Consumes: `IndexStore` (Task 1), `SymbolResolver.toDexType/classId` id formats.
- Produces: `IndexBuilder.build(RootNode root, IndexStore store)` — walks every `ClassNode` (incl. inners) × `MethodNode` with code: `mth.load()`, iterate `mth.getInstructions()`, `mth.unload()`; emits string rows and deduped outgoing edges; never throws (per-class try/catch, failures logged at debug).

Fixture class (lambda-free, no default methods):

```java
package dev.jadxmcp.fixture;

public class ApiClient {

	private static CryptoUtil crypto = new CryptoUtil();

	public byte[] call(String payload) {
		String url = "https://api.fixture.example.com/v1/data";
		byte[] data = payload.getBytes();
		return crypto.encode(data, crypto.secret());
	}

	public String describe() {
		return "api-client-ready";
	}
}
```

New `FixtureApk` constants:

```java
public static final String API_CLIENT_ID = "Ldev/jadxmcp/fixture/ApiClient;";
public static final String API_CLIENT_CALL_ID = "Ldev/jadxmcp/fixture/ApiClient;->call(Ljava/lang/String;)[B";
public static final String ENCODE_CALL_EDGE_DST = ENCODE_METHOD_ID; // ApiClient.call -> CryptoUtil.encode
public static final String API_CLIENT_DESCRIBE = "api-client-ready";
```

- [ ] **Step 1: Write failing test** `IndexBuilderTest`:

```java
package dev.jadxmcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.*;
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
		JadxDecompiler d = new JadxDecompiler(args);
		d.load();
		jadx = d;
	}

	@AfterEach
	void unload() {
		if (jadx != null) {
			jadx.close();
		}
	}

	@Test
	void collectsStringsAndEdgesWithoutDecompiling() throws Exception {
		IndexStore store = IndexStore.open(tmp, "hash1", jadx.getVersion());
		assertNotNull(store);
		IndexBuilder.build(jadx.getRoot(), store);
		store.markReady();

		List<StringRow> urls = store.searchStrings("api.fixture.example", false, 50);
		assertTrue(urls.stream().anyMatch(r -> r.methodId().equals(FixtureApk.API_CLIENT_CALL_ID)));
		// Phase-1-only signal (decompiled-scan constant) must also appear via CONST_STR
		assertTrue(urls.stream().anyMatch(r -> r.value().equals(FixtureApk.API_ENDPOINT)));
		assertTrue(store.searchStrings("api-client-ready", false, 10).size() >= 1);

		List<EdgeRow> out = store.outgoingOfMethod(FixtureApk.API_CLIENT_CALL_ID, 100);
		assertTrue(out.stream().anyMatch(e -> e.dstId().equals(FixtureApk.ENCODE_METHOD_ID)
				&& e.dstType().equals("method")));
		assertTrue(store.outgoingOfClass(FixtureApk.API_CLIENT_ID, 200).size() >= out.size());
		assertTrue(store.stringCount() > 0);
		assertTrue(store.edgeCount() > 0);
		store.close();
	}
}
```

- [ ] **Step 2: Run** → FAIL (`IndexBuilder` missing).
- [ ] **Step 3: Implement** `IndexBuilder`:

```java
static void build(RootNode root, IndexStore store) {
	for (ClassNode cls : root.getClasses(true)) {
		List<IndexStore.StringRow> strings = new ArrayList<>();
		Map<String, List<IndexStore.EdgeRow>> edges = new HashMap<>(); // src -> deduped dsts
		for (MethodNode mth : cls.getMethods()) {
			if (mth.isNoCode()) {
				continue;
			}
			String srcId = SymbolResolver.methodId(mth);
			Set<String> seen = new HashSet<>();
			try {
				mth.load();
				for (InsnNode insn : mth.getInstructions()) {
					switch (insn.getType()) {
						case CONST_STR -> {
							if (insn instanceof IndexInsnNode idx && idx.getIndex() instanceof String s && !s.isEmpty()) {
								strings.add(new IndexStore.StringRow(
										SymbolResolver.classId(cls),
										cls.getClassInfo().getAliasFullName(),
										srcId,
										mth.getMethodInfo().getAlias(),
										s));
							}
						}
						case INVOKE -> {
							if (insn instanceof InvokeNode inv) {
								addEdge(seen, edges, srcId, methodRef(inv.getCallMth()));
							}
						}
						case IGET, IPUT, SGET, SPUT -> {
							if (insn instanceof IndexInsnNode idx && idx.getIndex() instanceof FieldInfo fi) {
								addEdge(seen, edges, srcId, fieldRef(fi));
							}
						}
						case NEW_INSTANCE, CONST_CLASS -> {
							if (insn instanceof IndexInsnNode idx && idx.getIndex() instanceof ArgType t
									&& t.isObject() && t.isTypeKnown()) {
								addEdge(seen, edges, srcId, "class\u0000" + t.toString());
							}
						}
						default -> { }
					}
				}
			} catch (Exception e) {
				LOG.debug("index: failed to walk {}", srcId, e);
			} finally {
				mth.unload();
			}
		}
		if (!strings.isEmpty()) {
			store.insertStrings(strings);
		}
		edges.forEach(store::insertEdges);
	}
}

// methodRef(MethodInfo mi) = SymbolResolver.toDexType(mi.getDeclClass().makeRawFullName()) + "->" + mi.getShortId(), type "method"
// fieldRef(FieldInfo fi)  = SymbolResolver.toDexType(fi.getDeclClass().makeRawFullName()) + "->" + fi.getShortId(), type "field"
// class edge dst: ArgType.toString() of an object type already yields "Lcom/ex/Foo;" — normalize via
//   toDexType(t.toString().replace('/', '.')) when it lacks the L;...; wrapper; verify in test run.
```

`mth.unload()` frees the decoded instruction arrays; jadx usage info (`useIn` lists) is stored separately and survives. `mth.load()` on a method whose instructions were already loaded is a no-op reload — call pattern is always load→walk→unload.

- [ ] **Step 4: Run** → PASS (adjust `ArgType` normalization if the class-edge assertion differs).
- [ ] **Step 5: Commit** `feat: IndexBuilder — string + outgoing-edge extraction from raw dex instructions`

---

### Task 3: IndexedSearchService + search_strings upgrade

**Files:**
- Create: `src/main/java/dev/jadxmcp/core/IndexedSearchService.java`
- Modify: `src/main/java/dev/jadxmcp/model/StringMatch.java` (+`methodId`, `methodName`)
- Modify: `src/main/java/dev/jadxmcp/tools/SearchTools.java` (schema/description only — handler unchanged until Task 7 wiring)
- Test: `src/test/java/dev/jadxmcp/core/IndexedSearchServiceTest.java`

**Interfaces:**
- Consumes: `SearchService`, `JadxSearchService`, `IndexStore.searchStrings`.
- Produces: `IndexedSearchService implements SearchService` — ctor `(SearchService fallback, IndexStore store)`; `searchClasses/searchMethods` delegate; `searchStrings` uses store when `store.awaitReady(0)` else `fallback.searchStrings(...)`. Rows map to `StringMatch(value, classId, className, null, methodId, methodName)`.

`StringMatch` becomes:

```java
public record StringMatch(
		String value,
		String classId,
		String className,
		Integer line,
		String methodId,
		String methodName) {
}
```

`SearchTools.searchStrings` description update (schema unchanged, `maxClasses` marked deprecated):

```
"Search string constants across the whole input. Indexed when the Phase-2 index is ready "
+ "(no decompilation, results carry method context); otherwise falls back to scanning "
+ "decompiled sources. 'maxClasses' is deprecated and ignored."
```

- [ ] **Step 1: Failing test** — `IndexedSearchServiceTest`: load fixture jadx, build store via `IndexBuilder`, wrap `new IndexedSearchService(new JadxSearchService(root, cache), store)`; assert `searchStrings("api-client-ready", false, 10, 1)` returns a match with `methodId` != null and `methodName` != null; assert with `IndexStore` that is still BUILDING (fresh tmp store, not built) it delegates (returns Phase-1 shape with `line` populated for `API_ENDPOINT`, `methodId` null).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** service + model change. Fix any existing `ToolRegistryTest`/`JadxServiceTest` compilation breaks from the `StringMatch` constructor (add `null, null` args — Phase-1 path supplies `methodId=null`).
- [ ] **Step 4: Run** `--tests 'dev.jadxmcp.core.*Test' --tests 'dev.jadxmcp.tools.*Test' --rerun` → PASS.
- [ ] **Step 5: Commit** `feat: IndexedSearchService — indexed search_strings with method context + fallback`

---

### Task 4: Outgoing xrefs

**Files:**
- Modify: `src/main/java/dev/jadxmcp/core/XrefService.java` (`incoming` → `xrefs`)
- Modify: `src/main/java/dev/jadxmcp/core/JadxXrefService.java` (implements `xrefs`, incoming only)
- Create: `src/main/java/dev/jadxmcp/core/IndexedXrefService.java`
- Modify: `src/main/java/dev/jadxmcp/model/XrefInfo.java` (+`outgoing`, `outgoingCount`, `outgoingTruncated`)
- Modify: `src/main/java/dev/jadxmcp/tools/XrefTools.java` (call `xrefs`, description)
- Test: `src/test/java/dev/jadxmcp/core/IndexedXrefServiceTest.java`

**Interfaces:**
- `XrefService`: `XrefInfo xrefs(String symbolType, String symbolId, int limit)` (replaces `incoming`; throws `IllegalArgumentException` on bad type, returns null when symbol unknown — same contract).
- `JadxXrefService.xrefs` = current incoming logic, outgoing fields null.
- `IndexedXrefService(SearchService-independent)` ctor `(XrefService fallback, SymbolResolver resolver, IndexStore store)`; method outgoing = `outgoingOfMethod(id, limit+1)`; class outgoing = `outgoingOfClass(classId, limit+1)` (own methods only, not inners); field outgoing = null.
- Edge → `SymbolRef`: `dst_type` "method" → name = id between `->` and first `(`, className = id between `L` and `;` with `/`→`.`; "field" → name between `->` and `:`, className same; "class" → name = last `/`-segment of the type without `L;`, className null.

`XrefInfo` becomes:

```java
public record XrefInfo(
		SymbolRef symbol,
		List<SymbolRef> incoming,
		int incomingCount,
		Boolean incomingTruncated,
		List<SymbolRef> outgoing,
		Integer outgoingCount,
		Boolean outgoingTruncated) {
}
```

`XrefTools.get_xrefs` description: `"Incoming and outgoing xrefs of a symbol. Outgoing edges come from the Phase-2 index (present once index state is 'ready'); fields have no outgoing refs."` Handler: `session.xref().xrefs(...)`.

- [ ] **Step 1: Failing test** — fixture + built store: `xrefs("method", API_CLIENT_CALL_ID, 100)` returns non-null outgoing containing `ENCODE_METHOD_ID`, `outgoingCount >= 1`; `xrefs("class", CRYPTO_UTIL_ID, 100)` still returns incoming (existing usage info) with `FixtureMain`-side callers as before; `xrefs("field", <any fixture field id>, 100)` → outgoing null; store not ready → outgoing null but incoming intact.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**; update `ApkSession` xref wiring minimally to keep compilation (`JadxXrefService` implements new interface — no constructor change).
- [ ] **Step 4: Run** core+tools tests → PASS.
- [ ] **Step 5: Commit** `feat: get_xrefs outgoing edges via index (interface incoming() -> xrefs())`

---

### Task 5: IndexedCodeCache (persistent sources)

**Files:**
- Create: `src/main/java/dev/jadxmcp/core/IndexedCodeCache.java`
- Modify: `src/main/java/dev/jadxmcp/core/ApkSession.java` (use it when store present)
- Test: extend `src/test/java/dev/jadxmcp/core/JadxServiceTest.java`? No — service-level: new `IndexedCodeCacheTest`.

**Interfaces:**
- Produces: `IndexedCodeCache implements CodeCache`, ctor `(CodeCache delegate, IndexStore store)`; `getClassSource`: `classId = SymbolResolver.classId(cls.getTopParentClass())`; hit in `store.cachedSource` → return; miss → `delegate.getClassSource(cls)` + `store.putSource` (guard failures — never fail a read because the DB hiccups); `decompiledCount()` delegates.

- [ ] **Step 1: Failing test** — fixture jadx; `JadxCodeCache` delegate + built store: first `getClassSource(cryptoUtil)` contains `"XOR-CIPHER-V1"`, `decompiledCount()==1`; new store instance reopened from same dir → `IndexedCodeCache` with a fresh delegate returns same source with `decompiledCount()==0` (disk hit).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** + wire in `ApkSession` ctor (store != null → wrap).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: IndexedCodeCache — decompiled sources persist across sessions`

---

### Task 6: ResourceTableIndex + resource-id lookup

**Files:**
- Create: `src/main/java/dev/jadxmcp/core/ResourceTableIndex.java`
- Modify: `src/main/java/dev/jadxmcp/model/ResourceEntryInfo.java` (+`String id`)
- Modify: `src/main/java/dev/jadxmcp/tools/ResourceTools.java` (`get_resource` optional `id`, `list_resources` enrichment)
- Test: `src/test/java/dev/jadxmcp/core/ResourceTableIndexTest.java`

**Interfaces:**
- Produces:
  - `static ResourceTableIndex decode(JadxDecompiler jadx, Path apkPath)` → decodes `resources.arsc` via `jadx.getResourcesLoader().decodeTable(res, in)` (`in` = raw zip entry bytes of the arsc, same ZipFile pattern as `ResourceTools.readRawEntry`); returns empty index when no arsc / any failure.
  - `static ResourceTableIndex of(Iterable<ResourceEntry> entries)` — test/injection path.
  - `ResourceEntry findById(String id)` — parses `0x7f0e0001` (hex, case-insensitive) or decimal; null when absent.
  - `Integer idOf(String type, String key)` — for list enrichment.
- `get_resource` schema: `path` becomes optional; add `"id": {"type":"string","description":"Numeric resource id, e.g. '0x7f0e0001' or decimal — alternative to path"}`; handler: exactly one of path/id required else `INVALID_ARGUMENT`; id path → `session.resourceTable().findById`; unknown → `RESOURCE_NOT_FOUND`; entry with value (`getProtoValue()!=null && getValue()!=null` → that value; else `getSimpleValue()!=null` → `toString()`; else `"@"+typeName+"/"+keyName`) → `ResourceContent(name="@"+type+"/"+key, type=typeName, kind="value", text=value, ...)`; when a file resource `res/<typeName>/<keyName>.*` exists in `session.resources()` load it via the existing path branch instead.
- `list_resources`: enrich each entry with hex id via `idOf(parsedType, parsedKeyFromPath)` — only when the path is `res/<dir>/<file>`, `dir` starts with a known entry typeName and `file` base name (extension stripped) matches a key; otherwise `id` null.

- [ ] **Step 1: Failing test** — `ResourceTableIndexTest` uses injected entries:

```java
ResourceEntry e = new ResourceEntry(0x7f0e0001, "dev.jadxmcp.fixture", "string", "app_name", "");
// + second entry same id different config, and a "layout"/"activity_main" entry
ResourceTableIndex idx = ResourceTableIndex.of(List.of(e, e2, layout));
assertNotNull(idx.findById("0x7f0e0001"));
assertNotNull(idx.findById("2131623937"));      // decimal of 0x7f0e0001
assertEquals(0x7f0e0001, idx.idOf("string", "app_name"));
assertNull(idx.findById("0x1"));
assertNull(ResourceTableIndex.decode(emptyJadx, missingApk).findById("0x7f0e0001")); // no arsc -> empty
```

Plus `ToolRegistryTest`: `get_resource` with `id:"0x7f0e0001"` on fixture (no arsc) → error `RESOURCE_NOT_FOUND`; `get_resource` without path/id → `INVALID_ARGUMENT`; existing path-based case unchanged.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** index + tool changes; `ApkSession` gains `ResourceTableIndex resourceTable()` (built in ctor, empty-safe).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: resource-id lookup (0x7f...) via decoded resource table`

---

### Task 7: Wiring — JadxService, ApkSession, CLI, get_apk_info

**Files:**
- Modify: `src/main/java/dev/jadxmcp/core/JadxService.java` (IndexConfig ctor, sha256, background build)
- Modify: `src/main/java/dev/jadxmcp/core/ApkSession.java` (store + resourceTable fields, service selection)
- Modify: `src/main/java/dev/jadxmcp/cli/CliOptions.java` (+`--index-dir`, `--no-index`, usage)
- Modify: `src/main/java/dev/jadxmcp/Main.java` (pass `IndexConfig`)
- Modify: `src/main/java/dev/jadxmcp/model/ApkInfo.java` (+`IndexState index`), new `src/main/java/dev/jadxmcp/model/IndexState.java`
- Modify: `src/main/java/dev/jadxmcp/tools/ApkTools.java` (`apkInfo` includes index state)
- Test: new `src/test/java/dev/jadxmcp/cli/CliOptionsTest.java`; `ToolRegistryTest` integration additions

**Interfaces:**
- `JadxService()` no-arg → `this(IndexConfig.enabledDefault())` where `enabledDefault()` = `new IndexConfig(true, IndexConfig.defaultDir())`; `JadxService(IndexConfig cfg)`.
- `load()` additions after jadx loads: if `cfg.enabled()` → `sha256(normalized)` (streaming `MessageDigest`), `IndexStore.open(cfg.dir(), hash, jadxVersion)`; store non-null → `new ApkSession(jadx, path, size, store)`; if `store.needsBuild()` → daemon thread `{ IndexBuilder.build(root, store); store.markReady(); }` (catch → log warn, store.close(), rebuild session without store? no — leave disabled: services fall back via readiness check); store null → session without store. `ApkSession.close()` closes jadx + store.
- `ApkSession` ctor overload `(jadx, inputPath, sizeBytes, IndexStore store)` — null-safe: store==null → Phase-1 services + `IndexedCodeCache` skipped.
- `IndexState(String state, Long stringCount, Long edgeCount)` — state from `session.indexStore()`: null → `"disabled"`, `BUILDING` → `"building"` + null counts, else `"ready"` + counts. `ApkInfo` gains trailing `IndexState index` field.
- CLI parsing in both modes: `--index-dir <path>` (implies enabled), `--no-index` (implies disabled); usage text updated; mutual use → `CliException` (usage error).

- [ ] **Step 1: Failing tests**
  - `CliOptionsTest`: `parse("stdio","--no-index")` → disabled; `parse("server","--index-dir","/tmp/x")` → dir set + enabled; both flags → CliException; default → enabled + `~/.jadx-mcp/index`.
  - `ToolRegistryTest` (uses `new JadxService(new IndexConfig(true, tempDir))`): after `load_apk`, `get_apk_info` → `index.state` one of building/ready; poll `registry.call("get_apk_info", ...)` until `ready` (≤10s); then `search_strings` `query="api-client-ready"` → items have `methodId`; `get_xrefs` method `API_CLIENT_CALL_ID` → `outgoingCount >= 1`; disabled config → `index.state == "disabled"` and tools still work (Phase-1 shapes).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** all wiring.
- [ ] **Step 4: Run** full `test --rerun` → PASS.
- [ ] **Step 5: Commit** `feat: wire index lifecycle end-to-end (--index-dir/--no-index, index state in get_apk_info)`

---

### Task 8: Hermetic ITs + full verification

**Files:**
- Modify: `src/test/java/dev/jadxmcp/core/JadxServiceTest.java`, `tools/ToolRegistryTest.java`, `transport/HttpMcpServerIT.java`, `transport/TransportConsistencyIT.java` — replace `new JadxService()` with temp-dir or disabled config.
- Modify: `src/test/java/dev/jadxmcp/transport/StdioMcpServerIT.java`, `TransportConsistencyIT.java` — spawned jar args gain `--index-dir <build/fixtures/it-index>` (stdio side).

Rules: `TransportConsistencyIT` must use **disabled** on both transports so `get_apk_info.index` compares deterministically. `StdioMcpServerIT` uses an explicit build-dir `--index-dir`.

- [ ] **Step 1: Update ITs as above.**
- [ ] **Step 2:** Delete `build/fixtures`, run `test --rerun`; assert `build/fixtures/test.apk` exists, fresh timestamps in `build/test-results/test/*.xml`, all suites green (≥44 tests expected: 34 + ~10 new).
- [ ] **Step 3: Commit** `test: hermetic index dirs in all suites; full --rerun verification`

---

### Task 9: Docs + version bump 0.2.0

**Files:**
- Modify: `build.gradle.kts` (`resolveAppVersion` fallback `0.1.0` → `0.2.0`)
- Modify: `README.md` (CLI table + flags, tools table: get_xrefs/search_strings/get_resource/list_resources/get_apk_info rows, "Known limitations" rewrite, Phase 2 section status, architecture tree, fat jar size)
- Modify: `AGENTS.md` (component table + index files, test suite table + counts, build-cache note unchanged, Phase-2 seam note now realized)
- Modify: `docs/superpowers/specs/2026-09-11-phase2-index-design.md` status line → implemented

- [ ] **Step 1:** Version fallback bump; `build -PappVersion=0.2.0`; verify `java -jar build/libs/jadx-mcp-0.2.0.jar --version` prints `0.2.0` (stderr) and jar exists; rebuild default (no -P) also yields 0.2.0.
- [ ] **Step 2:** README/AGENTS updates (table rows exact; no marketing).
- [ ] **Step 3:** `git add -A && git commit -m "feat: Phase 2 index track (SQLite string index, outgoing xrefs, resource-id lookup, persistent code cache) — v0.2.0"` — post-commit hook auto-tags `v0.2.0`; verify with `git tag --list v0.2.0`.

## Self-Review

- Spec coverage: string index (T1–3), outgoing xrefs (T2/T4), resource-id (T6), disk code cache (T5), CLI flags + indexState + fallback + failure → disabled (T7), hermetic tests (T8), docs/version (T9). Multi-APK/rename/auth are declared non-goals in spec. ✓
- Placeholders: none — every step carries code or exact SQL/assertions; two investigation notes (`ArgType` class-id normalization, `RawValue.toString` rendering) are pinned by adjacent assertions that fail if the assumption is wrong.
- Type consistency: `StringRow(classId, className, methodId, methodName, value)` / `EdgeRow(dstId, dstType)` used identically in T1/T2/T3/T4; `xrefs(...)` replaces `incoming(...)` everywhere (T4, T7); `IndexConfig(boolean, Path)` consistent T1/T7/T8.
