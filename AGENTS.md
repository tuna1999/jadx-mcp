# Repository Guidelines

AI-assistant guide for **jadx-mcp** — an MCP server exposing JADX decompiler functionality to AI agents over STDIO and Streamable HTTP.

## Project Overview

`jadx-mcp` wraps `jadx-core` 1.5.6 via its Java API (no CLI subprocess) and exposes 14 reverse-engineering tools (list classes, get decompiled source, search strings, xrefs, manifest, resources…) to MCP clients. One `ToolRegistry` serves two transports from a single fat jar:

- `java -jar jadx-mcp-<v>.jar stdio [--input <apk>]` — MCP JSON-RPC on stdout only; logs on stderr.
- `java -jar jadx-mcp-<v>.jar server [--input <apk>] [--host H] [--port P]` — Streamable HTTP at `http://H:P/mcp` (Jetty), loopback by default.

## Architecture & Data Flow

```
MCP client → {StdioMcpServer | HttpMcpServer (Jetty /mcp)}
           → McpServers (shared sync-server factory)
           → ToolRegistry (14 ToolDefinitions; transport-agnostic)
           → JadxService.load() → ApkSession
              ├─ SymbolResolver   (DEX-style ids ⇄ jadx nodes)
              ├─ CodeCache / SearchService / XrefService (interfaces, Phase-2 swappable)
              └─ jadx-core JadxDecompiler
```

Key data-flow facts:

- **One active APK per process**: `JadxService` holds a `volatile ApkSession`; `load()` (synchronized) validates magic bytes (dex/zip/class), builds `JadxDecompiler` with `threadsCount = clamp(1, cpus-1, 8)`, closes the previous session. `load_apk` works at runtime without `--input`.
- **Xrefs are free**: jadx computes usage info at `load()` from raw dex; `JadxXrefService.incoming()` reads `getUseIn()` without decompiling.
- **Decompilation is lazy + cached**: `JadxCodeCache.getClassSource(classNode)` decompiles top-level classes on demand and remembers them; `search_strings` decompiles up to `maxClassesToScan` classes once per session, then scans string literals.
- **Stable DEX-style symbol ids** everywhere: class `Lcom/ex/Foo;`, method `Lcom/ex/Foo;->decrypt([B[B)[B`, field `Lcom/ex/Foo;->key:[B`. `SymbolResolver` also accepts dotted names. IDs survive deobfuscation; all DTOs carry `id` + human-readable `name`/`className`.
- **Error discipline**: core throws `JadxServiceException(ErrorCode)`, tools throw `ToolException`; `ToolRegistry.call()` maps everything to `CallToolResult` with `structuredContent` = `{"error":{"code","message"}}` — never JVM stack traces in responses. Codes: `NO_APK_LOADED, FILE_NOT_FOUND, INVALID_INPUT_FILE, CLASS_NOT_FOUND, METHOD_NOT_FOUND, FIELD_NOT_FOUND, RESOURCE_NOT_FOUND, INVALID_SYMBOL_ID, INVALID_ARGUMENT, DECOMPILATION_FAILED, MANIFEST_NOT_FOUND`.
- **Pagination/truncation**: every list/search result embeds a `Page {offset, limit, total, hasMore, nextOffset}` (default limit 100); sources take `maxChars` and return `truncated`/`totalChars` metadata.

## Key Directories

| Path | Purpose |
|---|---|
| `src/main/java/dev/jadxmcp/` | root package: `Main`, `cli/`, `logging/`, `core/`, `tools/`, `transport/`, `model/`, `util/` |
| `core/` | `JadxService`, `ApkSession`, `SymbolResolver` + Phase-2 interfaces (`CodeCache`, `SearchService`, `XrefService`) with jadx impls (`Jadx*`) |
| `tools/` | `ToolRegistry` + 6 grouped providers: `ApkTools`, `ClassTools`, `MethodTools`, `SearchTools`, `XrefTools`, `ResourceTools` |
| `transport/` | `McpServers` (shared factory), `StdioMcpServer`, `HttpMcpServer`, `Authenticator` (auth extension point, ships null) |
| `model/` | Jackson-3 records (DTOs) + `ErrorCode` + `Page` — the only shapes that cross the MCP boundary |
| `src/test/fixture-src/` | tiny legal Java fixture classes compiled into the test APK at test time |
| `src/test/java/dev/jadxmcp/fixture/` | `FixtureApk` (javac → D8 → AXML → zip) + `AxmlWriter` (hand-rolled binary Android XML) |
| `.github/workflows/` | `ci.yml` (build/test/smoke), `release.yml` (tag → GitHub Release) |

## Development Commands

```bash
# Windows/msys note: gradlew.bat is unusable from msys bash; invoke the wrapper directly:
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain <task>

./gradlew build                                # build + all tests + fat jar (Linux/macOS/Git-Bash)
./gradlew test --rerun                         # force REAL test execution (see gotcha below)
./gradlew build -PappVersion=1.2.3             # versioned jar -> build/libs/jadx-mcp-1.2.3.jar

java -jar build/libs/jadx-mcp-<v>.jar --help
java -jar build/libs/jadx-mcp-<v>.jar --version   # must print the build version
java -jar build/libs/jadx-mcp-<v>.jar stdio --input build/fixtures/test.apk
```

**Build-cache gotcha**: `gradle.properties` enables `org.gradle.caching=true`; a cache-HIT `test` restores result XMLs *without running tests* (fixture APK will be missing). When verifying, use `test --rerun` and check `build/fixtures/test.apk` exists + fresh timestamps inside `build/test-results/test/*.xml`. CI guards this explicitly.

Exit codes (`Main`): 0 ok, 1 fatal, 2 CLI usage error, 3 `--input` load failure, 130 interrupted.

## Code Conventions & Common Patterns

- **Java records** for all DTOs (`model/`), `@JsonInclude(NON_NULL)`; Jackson 3 (`tools.jackson.*`) shared with the MCP SDK — do not add Jackson 2.
- **Tabs** for indentation in Java; English javadoc on public classes; one class per file.
- **Manual constructor injection** — no DI framework, no Spring. `Main` composes: `JadxService` → `ToolRegistry(jadx)` → `McpServers.syncServer(registry, provider)`.
- **Adding a tool**: implement it in the appropriate `*Tools` class as a `ToolDefinition(name, description, jsonSchemaTextBlock, handler)`; the handler takes `Args` (typed getters with `INVALID_ARGUMENT` on bad input) and returns a DTO record. `ToolRegistry` registers via `LinkedHashMap` and rejects duplicate names. Add tests in `ToolRegistryTest` — no transport code needed.
- **Never leak jadx types** across the tool boundary; convert to `model/` records in `core/` or `tools/`.
- **Concurrency**: session swap is `synchronized` on `JadxService`; `JadxCodeCache` uses `ConcurrentHashMap.newKeySet`; `ToolRegistry` tracks in-flight calls with an `AtomicLong` + `awaitIdle(ms)` so STDIO shutdown drains running tool calls before closing.
- **Logging**: SLF4J only; `Logging.init` forces slf4j-simple to `System.err` (stdout is reserved for MCP in stdio mode). No `System.out` in main code.
- **HTTP security default**: bind `127.0.0.1`; any non-loopback `--host` must log a network-exposure warning (`CliOptions.bindsNonLoopback()`). Future auth goes through `transport/Authenticator`, not core changes.

## Important Files

| File | Role |
|---|---|
| `src/main/java/dev/jadxmcp/Main.java` | entry point, CLI dispatch, exit codes, `--input` preload |
| `src/main/java/dev/jadxmcp/core/JadxService.java` | APK lifecycle, validation, error codes |
| `src/main/java/dev/jadxmcp/core/SymbolResolver.java` | DEX-id ⇄ jadx node mapping; the only place id parsing lives |
| `src/main/java/dev/jadxmcp/tools/ToolRegistry.java` | single source of truth for all 14 tools |
| `src/main/java/dev/jadxmcp/transport/McpServers.java` | shared factory — identical tool wiring for both transports |
| `build.gradle.kts` | toolchain JDK 25 / `--release 17` bytecode; custom `fatJar` + `mergeServiceFiles` (unions `META-INF/services/**` so ServiceLoader providers — jadx plugins, SLF4J, MCP JSON mapper — survive shading) |
| `src/test/java/dev/jadxmcp/fixture/FixtureApk.java` | generates `build/fixtures/test.apk` (javac `--release 8` → R8/D8 `--min-api 26` → hand-rolled binary AXML → zip); hardcoded cache path assumes CWD = project root |

## Runtime/Tooling Preferences

- **Java 17+ runtime** (bytecode pinned `--release 17`); build toolchain is JDK 25. Gradle 9.7.1 Kotlin DSL, single module.
- Dependencies are deliberately pinned: jadx 1.5.6 (core + `jadx-dex-input` + `jadx-java-input` + `jadx-kotlin-metadata` — dex-input is REQUIRED or `load()` returns 0 classes), MCP SDK 2.0.1, Jetty 12 ee10 (jakarta servlet), slf4j-simple 2.0.17. Test-only: JUnit 6, `com.android.tools:r8` (from `google()` repo) for fixture dexing.
- Version resolution order: `-PappVersion=...` > tag ref `v<semver>` > env `JADXMCP_VERSION` > `0.1.0`. The jar filename embeds the version; CI asserts `--version` output equals it.
- No Spring Boot, no database, no GUI, no subprocess decompilation.

## Testing & QA

JUnit 6 (Jupiter), 34 tests across 5 suites:

| Suite | Layer | What it proves |
|---|---|---|
| `core/JadxServiceTest` (12) | in-JVM | load/invalid-file, class/method lookup roundtrips, decompile, manifest, xrefs, string search, resources, session replace |
| `tools/ToolRegistryTest` (15) | in-JVM | all 14 tools transport-independently; error codes, pagination, truncation |
| `transport/StdioMcpServerIT` (3) | real process | spawns the fat jar (path via `jadxmcp.jar` sysprop set by Gradle); handshake, tool call, **stdout purity** (every line valid JSON), clean EOF exit |
| `transport/HttpMcpServerIT` (2) | in-JVM Jetty + HttpClient | initialize, `Mcp-Session-Id` handling, tool call, structured errors |
| `transport/TransportConsistencyIT` (2) | both | identical structured payloads modulo volatile fields |

Conventions: fixture-driven (constants in `FixtureApk`: `CRYPTO_UTIL_ID`, `ENCODE_METHOD_ID`, `API_ENDPOINT`); plain JUnit asserts; ITs reuse `StdioTestClient`/`HttpMcpClient` helpers. Any new tool must get a `ToolRegistryTest` case; any transport-visible change must keep `TransportConsistencyIT` green. Keep fixture classes free of lambdas/default methods (dexed with `--min-api 26`).

CI (`.github/workflows/ci.yml`) additionally smoke-tests both transports against the packaged jar and requires ≥34 executed tests; releases (tags `v*`) publish `jadx-mcp-<v>.jar` + SHA-256 to GitHub Releases.

## Versioning Policy (AI-decided)

The AI assistant working on this repo **decides autonomously whether to bump the version** based on the nature of the changes — no version bump is automatic, and none should be requested from the user. Judge each completed change set against this scale:

| Change | Bump |
|---|---|
| New MCP tool, new CLI flag, new DTO field, new transport capability | **minor** (`0.2.0` → `0.3.0`) |
| Bug fix, error-message/wording fix, refactor with identical external behavior, test/CI/docs-only | **patch** (`0.2.0` → `0.2.1`), or **no bump** if nothing user-visible changed |
| Breaking change to tool schemas, error codes, CLI syntax, exit codes, or DTO shapes | **major** (`0.2.0` → `1.0.0`) |

Rules:

- Decide at the END of a change set, after tests pass — bump only when the change is user/agent-visible (tool behavior, CLI, packaging). Internal-only refactors do not bump.
- Apply via `-PappVersion=<new>` when building/committing the change (default fallback is `0.1.0` in `build.gradle.kts` — update the fallback too if the baseline moved).
- `--version` output, jar filename, and GitHub Release artifacts must all agree; CI asserts this. After a bump, verify `java -jar build/libs/jadx-mcp-<v>.jar --version`.
- **Auto-tagging**: `.githooks/post-commit` (enable once per clone: `git config core.hooksPath .githooks`) reads the version fallback in `build.gradle.kts` and tags `v<version>` automatically on the first commit that carries a new version. No manual `git tag` needed.
- Publishing = `git push origin v<version>` (release workflow builds, verifies, attaches jar + SHA-256). Push the tag when the user asks for a release; version bumps and tag creation are the AI's call.
