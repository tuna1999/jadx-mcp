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

| Component        | Choice |
|------------------|--------|
| Java             | 17+ bytecode (built with a modern JDK) |
| Build            | Gradle (Kotlin DSL), `./gradlew build` |
| Decompiler       | `jadx-core` 1.5.6 (+ `jadx-dex-input`, `jadx-java-input`, `jadx-kotlin-metadata` plugins) via the Java API — no CLI subprocess |
| MCP              | official MCP Java SDK 2.0.1 (`io.modelcontextprotocol.sdk:mcp`, Streamable HTTP + STDIO providers) |
| JSON             | Jackson 3 (same stack as the SDK) |
| Logging          | SLF4J + slf4j-simple (stderr only) |
| HTTP container   | embedded Jetty 12 (ee10 servlet) |
| Tests            | JUnit 6; test APK fixture is generated at test time (javac → D8 → zip) |

No Spring Boot, no database.

## Build

```bash
./gradlew build          # compiles, runs all tests, produces build/libs/jadx-mcp.jar
```

The runnable fat jar is `build/libs/jadx-mcp.jar` (~22 MB).

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
│   ├── CodeCache.java            interface (Phase 2: disk/SQLite cache)
│   ├── JadxCodeCache.java        jadx in-memory code cache adapter
│   ├── SearchService.java        interface (Phase 2: FTS5/SQLite index)
│   ├── JadxSearchService.java    in-memory scan implementation
│   ├── XrefService.java          interface (Phase 2: persistent xref index)
│   └── JadxXrefService.java      jadx usage-info based xrefs
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

## Tools (Phase 1 — 14 tools)

All list/search tools paginate (`offset`/`limit`) and return `page` metadata.
Sources support `maxChars` truncation with explicit `truncated`/`totalChars`
fields. Errors are structured: `{"error": {"code": "...", "message": "..."}}`
with codes like `NO_APK_LOADED`, `FILE_NOT_FOUND`, `INVALID_INPUT_FILE`,
`CLASS_NOT_FOUND`, `METHOD_NOT_FOUND`, `FIELD_NOT_FOUND`, `RESOURCE_NOT_FOUND`,
`INVALID_SYMBOL_ID`, `INVALID_ARGUMENT`, `DECOMPILATION_FAILED`,
`MANIFEST_NOT_FOUND`.

| Tool | Purpose |
|------|---------|
| `load_apk` | Load/replace the active APK/DEX/JAR; returns metadata |
| `get_apk_info` | Counts, manifest package, jadx version |
| `list_packages` | Packages + class counts |
| `list_classes` | Paginated classes, `package` prefix + `query` filters |
| `get_class_outline` | Fields/methods/supertype/inners — no source (preferred inspection API) |
| `get_class_source` | Decompiled class source, `maxChars` truncation |
| `get_method_source` | Single method by stable id — primary code-reading API |
| `search_classes` | Class/package name substring search |
| `search_methods` | Method name/signature search with optional class filter |
| `search_strings` | String constants in decompiled code (lazy, cached) |
| `get_xrefs` | Incoming usages of a class/method/field |
| `get_manifest` | Decoded AndroidManifest.xml + parsed package/version |
| `list_resources` | Resource entries with `query`/`type` filters |
| `get_resource` | One resource by path (text or base64, truncation metadata) |

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
- **ToolRegistryTest** (15) — every tool via the registry, independent of
  transport; error codes; pagination; truncation.
- **StdioMcpServerIT** (3) — spawns the real fat jar: handshake, tools/list,
  tool call, stdout purity (every stdout line must be JSON), `load_apk`
  without `--input`, clean exit on stdin EOF.
- **HttpMcpServerIT** (2) — real Jetty + Streamable HTTP: initialize, session
  handling, tool call, structured errors.
- **TransportConsistencyIT** (2) — identical structured payloads through both
  transports (volatile fields like timestamps excluded).

The test APK fixture (`build/fixtures/test.apk`, ~3 KB) is generated at test
time from `src/test/fixture-src` (javac → D8 dex → hand-encoded binary
AndroidManifest.xml → zip). No binaries are committed.

## Security

- HTTP binds to `127.0.0.1` by default; any other bind is an explicit CLI
  argument and logs a warning.
- `load_apk` is the only path that touches the filesystem; resource tools read
  entries from the loaded APK only.
- The `Authenticator` interface in `transport/` is the extension point for
  HTTP authentication — it can be implemented without touching tools or core.

## Known limitations (Phase 1)

- One active APK per process; multi-session is future work (`ApkSession` is
  designed for it).
- `search_strings` decompiles lazily once per session (cached in memory); first
  search on a huge APK can take a while. Phase 2 moves this behind FTS5.
- Xrefs are incoming-only; outgoing xrefs are planned (interface allows it).
- `get_resource` addresses resources by path; lookup by numeric resource id
  (`0x7f...`) arrives with the Phase 2 resource index.
- No authentication on HTTP yet (loopback default + extension point).
- `get_class_source` for a single inner class returns the enclosing top-level
  class source (jadx decompiles inner classes together).

## Phase 2 ideas

SQLite/FTS5 string + symbol index, persistent xref index with outgoing edges,
disk-backed code cache, resource-id lookup, multi-APK sessions, optional
rename/refactor support, authentication.
