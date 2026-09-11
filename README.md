# jadx-mcp

A [JADX](https://github.com/skylot/jadx)-powered MCP server for AI coding and
reverse-engineering agents. One codebase, two transports:

- **STDIO** — headless `jadx-mcp stdio` for Claude Code, Codex, Pi and any MCP
client that spawns local processes. STDOUT carries MCP JSON-RPC only; logs go
to STDERR.
- **HTTP** — standalone `jadx-mcp server` exposing the MCP Streamable HTTP
transport at `http://<host>:<port>/mcp` (embedded Jetty, loopback by default).

Both transports expose exactly the same tools through one shared
`ToolRegistry`. There is no transport-specific JADX logic.

Built from scratch on:


| Component      | Choice                                                                                                                         |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Java           | 17+ bytecode (built with a modern JDK)                                                                                         |
| Build          | Gradle (Kotlin DSL), `./gradlew build`                                                                                         |
| Decompiler     | `jadx-core` 1.5.6 (+ `jadx-dex-input`, `jadx-java-input`, `jadx-kotlin-metadata` plugins) via the Java API — no CLI subprocess |
| MCP            | official MCP Java SDK 2.0.1 (`io.modelcontextprotocol.sdk:mcp`, Streamable HTTP + STDIO providers)                             |
| Index          | SQLite (`org.xerial:sqlite-jdbc` 3.53.4.0) — per-APK string/xref/code-cache index, persisted across sessions                  |
| JSON           | Jackson 3 (same stack as the SDK)                                                                                              |
| Logging        | SLF4J + slf4j-simple (stderr only)                                                                                             |
| HTTP container | embedded Jetty 12 (ee10 servlet)                                                                                               |
| Tests          | JUnit 6; test APK fixture is generated at test time (javac → D8 → zip)                                                         |


No Spring Boot. The only embedded database is SQLite, used as a local
per-input cache under `~/.jadx-mcp/index` (disable with `--no-index`).

## Build

```bash
./gradlew build                          # version mặc định 0.2.0
./gradlew build -PappVersion=1.2.3      # build theo version chỉ định
```

The runnable fat jar is `build/libs/jadx-mcp-<version>.jar` (~34 MB).
Version được resolve theo thứ tự: `-PappVersion=...` &gt; tag `v*` (CI) &gt;
env `JADXMCP_VERSION` &gt; `0.2.0`. `java -jar jadx-mcp-<v>.jar --version` in ra đúng version đó.

## Usage

### STDIO

```bash
java -jar jadx-mcp.jar stdio                          # load APK later via load_apk tool
java -jar jadx-mcp.jar stdio --input /samples/test.apk
```

### HTTP (Streamable MCP)

```bash
java -jar jadx-mcp.jar server --input /samples/test.apk --host 127.0.0.1 --port 8650
# MCP endpoint: http://127.0.0.1:8650/mcp
```

`--host` defaults to `127.0.0.1`. Binding to anything else (e.g. `0.0.0.0`) is
explicit and logs a network-exposure warning. `--log-level` sets
`trace|debug|info|warn|error`.

### CLI reference

| Flag | Description |
|------|-------------|
| `stdio` \| `server` | transport mode (required first argument) |
| `--input <path>` | APK/DEX/JAR to preload at startup (optional; `load_apk` tool can load later) |
| `--host <addr>` | HTTP bind address, default `127.0.0.1` (non-loopback logs a warning) |
| `--log-level <lvl>` | `trace|debug|info|warn|error`, default `info` |
| `--index-dir <dir>` | directory for the Phase 2 SQLite index, default `~/.jadx-mcp/index` |
| `--no-index` | disable the index entirely (pure in-memory Phase 1 behavior) |
| `--port <n>` | HTTP port, default `8650` |
| `-h`, `--help`, `help` | usage, exit 0 |
| `-V`, `--version`, `version` | print build version (must match jar filename), exit 0 |

Exit codes: `0` ok / help / version · `1` fatal error · `2` CLI usage error ·
`3` startup `--input` failed to load · `130` interrupted (SIGINT).

Note: CLI output (usage, `--version`) is printed to **stderr**; stdout is
reserved for MCP JSON-RPC in stdio mode.

### Claude Code / Claude Desktop configuration

STDIO server (recommended for local use):

```json
{
  "mcpServers": {
    "jadx-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "D:/tools/jadx-mcp.jar",
        "stdio"
      ]
    }
  }
}
```

HTTP server:

```json
{
  "mcpServers": {
    "jadx-mcp": {
      "type": "http",
      "url": "http://127.0.0.1:8650/mcp"
    }
  }
}
```

(Start `jadx-mcp server --port 8650` separately; the client only connects.)

## Architecture

```
                     AI Agent
                         |
             +-----------+-----------+
             |                       |
          STDIO MCP                HTTP MCP  (Streamable HTTP, Jetty)
             |                       |
             +-----------+-----------+
                         |
                   ToolRegistry      (one shared tool set)
                         |
                   Service Layer     (JadxService / ApkSession / SymbolResolver)
                         |
                    JADX Core       (embedded via Java API)
                         |
                    APK / DEX / JAR
```

```
src/main/java/dev/jadxmcp/
├── Main.java                     entry point, exit codes, startup --input
├── cli/CliOptions.java           argument parsing
├── logging/Logging.java          stderr-only log configuration
├── core/
│   ├── JadxService.java          load/replace/close inputs, validation, error codes
│   ├── ApkSession.java           one loaded input + derived services
│   ├── SymbolResolver.java       DEX-style ids <-> jadx nodes
│   ├── CodeCache.java            interface (Phase 2 seam)
│   ├── JadxCodeCache.java        jadx in-memory code cache adapter
│   ├── IndexedCodeCache.java     Phase 2: sources persisted in SQLite
│   ├── SearchService.java        interface (Phase 2 seam)
│   ├── JadxSearchService.java    in-memory scan implementation (fallback)
│   ├── IndexedSearchService.java Phase 2: indexed search_strings
│   ├── XrefService.java          interface (Phase 2 seam, both directions)
│   ├── JadxXrefService.java      jadx usage-info based incoming xrefs
│   ├── IndexedXrefService.java   Phase 2: + outgoing edges from the index
│   ├── IndexConfig.java          index enable/disable + directory
│   ├── IndexStore.java           SQLite store (strings/edges/sources/meta)
│   ├── IndexBuilder.java         raw-dex instruction walk -> index rows
│   └── ResourceTableIndex.java   decoded resources.arsc -> 0x7f... lookup
├── tools/
│   ├── ToolRegistry.java         ONE registration layer, transport-agnostic
│   ├── ToolDefinition.java       name + description + JSON schema + handler
│   ├── ToolException.java        machine-readable tool errors
│   ├── Args.java                 typed argument access
│   ├── ApkTools.java             load_apk, get_apk_info, list_packages, get_manifest
│   ├── ClassTools.java           list_classes, get_class_outline, get_class_source
│   ├── MethodTools.java          get_method_source
│   ├── SearchTools.java          search_classes, search_methods, search_strings
│   ├── XrefTools.java            get_xrefs
│   └── ResourceTools.java        list_resources, get_resource
├── transport/
│   ├── McpServers.java           shared MCP server factory (identical wiring)
│   ├── StdioMcpServer.java       SDK stdio provider, drains in-flight calls on EOF
│   ├── HttpMcpServer.java        SDK streamable provider on embedded Jetty
│   └── Authenticator.java        auth extension point (no core changes needed)
├── model/                        stable DTOs (records) returned to agents
└── util/                         JSON mapper, version
```

## Tools (14 tools)

All list/search tools paginate (`offset`/`limit`) and return `page` metadata.
Sources support `maxChars` truncation with explicit `truncated`/`totalChars`
fields. Errors are structured: `{"error": {"code": "...", "message": "..."}}`
with codes like `NO_APK_LOADED`, `FILE_NOT_FOUND`, `INVALID_INPUT_FILE`,
`CLASS_NOT_FOUND`, `METHOD_NOT_FOUND`, `FIELD_NOT_FOUND`, `RESOURCE_NOT_FOUND`,
`INVALID_SYMBOL_ID`, `INVALID_ARGUMENT`, `DECOMPILATION_FAILED`,
`MANIFEST_NOT_FOUND`.

### Phase 2 index

On `load_apk`, jadx-mcp walks raw dex instructions once (no decompilation) and
builds a SQLite index keyed by the file's SHA-256 under the index directory:
string constants per method, outgoing reference edges, and a cache of
decompiled sources. The index persists across sessions — reloading the same
input is instant. With `--no-index` (or if the index cannot be opened) every
tool falls back to the Phase 1 in-memory implementations.

| Tool                | Purpose                                                                |
| ------------------- | ---------------------------------------------------------------------- |
| `load_apk`          | Load/replace the active APK/DEX/JAR; returns metadata                  |
| `get_apk_info`      | Counts, manifest package, jadx version, index state (`building/ready/disabled`) |
| `list_packages`     | Packages + class counts                                                |
| `list_classes`      | Paginated classes, `package` prefix + `query` filters                  |
| `get_class_outline` | Fields/methods/supertype/inners — no source (preferred inspection API) |
| `get_class_source`  | Decompiled class source, `maxChars` truncation (disk-cached)           |
| `get_method_source` | Single method by stable id — primary code-reading API                  |
| `search_classes`    | Class/package name substring search                                    |
| `search_methods`    | Method name/signature search with optional class filter                |
| `search_strings`    | Indexed string-constant search with method context (decompile-free when index ready) |
| `get_xrefs`         | Incoming + outgoing usages of a class/method/field                     |
| `get_manifest`      | Decoded AndroidManifest.xml + parsed package/version                   |
| `list_resources`    | Resource entries with `query`/`type` filters + numeric ids            |
| `get_resource`      | One resource by `path` or numeric `id` (text/base64/value, truncation) |


### Stable symbol ids

DEX-style identities survive deobfuscation and renaming:

```
class:  Lcom/example/Foo;
method: Lcom/example/Foo;->decrypt([B[B)[B
field:  Lcom/example/Foo;->key:[B
```

Dotted names (`com.example.Foo`) are accepted as class lookup input. Every
returned object carries `id` plus human-readable `name`/`className`.

Recommended agent flow:

```
list_classes → get_class_outline → get_method_source → get_xrefs
```

## Tests

```bash
./gradlew test
```

- **JadxServiceTest** (12) — load success/failure, class/method lookup,
decompilation, manifest, xrefs, string search, resource access, session
replacement.
- **IndexStoreTest** (5) — SQLite lifecycle: build, persist, reopen, version
mismatch rebuild, unwritable dir fallback.
- **IndexBuilderTest** (2) — instruction walk collects strings + edges without
decompiling; deterministic rebuild.
- **IndexedSearchServiceTest** (3) — method context from index; delegation to
Phase 1 while building.
- **IndexedXrefServiceTest** (4) — outgoing edges, field shape, truncation.
- **IndexedCodeCacheTest** (1) — source survives store reopen without
re-decompilation.
- **ResourceTableIndexTest** (3) — hex/decimal id lookup, type/key mapping,
empty table on arsc-less input.
- **CliOptionsTest** (5) — `--index-dir` / `--no-index` parsing.
- **ToolRegistryTest** (18) — every tool via the registry, independent of
transport; error codes; pagination; truncation; index lifecycle + fallback.
- **StdioMcpServerIT** (3) — spawns the real fat jar: handshake, tools/list,
tool call, stdout purity (every stdout line must be JSON), `load_apk`
without `--input`, clean exit on stdin EOF.
- **HttpMcpServerIT** (2) — real Jetty + Streamable HTTP: initialize, session
handling, tool call, structured errors.
- **TransportConsistencyIT** (2) — identical structured payloads through both
transports (volatile fields like timestamps excluded).

## Security

- HTTP binds to `127.0.0.1` by default; any other bind is an explicit CLI
argument and logs a warning.
- `load_apk` is the only path that touches the filesystem besides the index
directory (SQLite caches keyed by input SHA-256, `--no-index` to disable);
resource tools read entries from the loaded APK only.
- The `Authenticator` interface in `transport/` is the extension point for
HTTP authentication — it can be implemented without touching tools or core.

## Known limitations

- One active APK per process; multi-session is future work (`ApkSession` is
designed for it).
- The first `load_apk` of an unseen input builds its index synchronously by
walking all dex instructions — proportional to code size, then persisted
(repeat loads are instant).
- Indexed `search_strings` results carry no line numbers (they come from raw
dex, not decompiled text); the Phase 1 fallback does.
- Outgoing xref destinations are raw ids; external symbols (framework
classes) are not resolvable to sources.
- Index directory growth is not managed automatically; deleting files there
is safe (they rebuild on next load).
- No authentication on HTTP yet (loopback default + extension point).
- `get_class_source` for a single inner class returns the enclosing top-level
class source (jadx decompiles inner classes together).

## Phase 2 status and remaining ideas

Delivered in 0.2.0: SQLite string index with method context, outgoing xref
edges, resource-id (`0x7f...`) lookup, persistent decompiled-source cache,
`--index-dir` / `--no-index` CLI flags, index state in `get_apk_info`.

Remaining ideas: multi-APK sessions, optional rename/deobfuscation support,
HTTP authentication, FTS5 trigram acceleration for very large string tables.