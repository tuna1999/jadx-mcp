# Phase 2 — SQLite Index Track (Design)

Date: 2026-09-11
Status: implemented (commit series through 05c7f7d, released as v0.2.0);
design deviation: the index build runs synchronously inside `load()` instead
of a background thread — `MethodNode.load()/unload()` on a builder thread
races tool threads decompiling the same jadx nodes and corrupts output;
persisted indexes keep repeat loads instant.

Target version: 0.2.0 (minor: new capability, additive DTO fields, new CLI flags, no breaking change)

## Goal

Make `search_strings` fast on any APK without decompiling, add outgoing xrefs,
support resource lookup by numeric id (`0x7f...`), and persist decompiled
sources across restarts — all behind the Phase-2 seams (`CodeCache`,
`SearchService`, `XrefService`) so tool code stays transport-agnostic.

Non-goals (deferred): multi-APK sessions, rename/deobfuscation support, HTTP
authentication, symbol (class/method) indexing — in-memory scan over jadx nodes
is already fast because it never decompiles.

## Verified API evidence (jadx-core 1.5.6, checked via javap)

| Need | API |
|---|---|
| Strings + outgoing refs without decompiling | `MethodNode.load()` → `getInstructions()` (`InsnNode[]`), walk `InsnType.CONST_STR` / `INVOKE` / `IGET`,`IPUT`,`SGET`,`SPUT` / `NEW_INSTANCE` / `CONST_CLASS`; `IndexInsnNode.getIndex()` (String / FieldInfo / ArgType), `InvokeNode.getCallMth()` (MethodInfo); then `MethodNode.unload()` |
| Resource-id lookup | `JadxDecompiler.getResourcesLoader().decodeTable(ResourceFile, InputStream)` → `IResTableParser.getResStorage()` → `ResourceStorage.getResources()` iterates `ResourceEntry{getId(), getTypeName(), getKeyName(), getConfig(), getSimpleValue(), getNamedValues()}` |
| SQLite | `org.xerial:sqlite-jdbc` 3.53.4.0 (latest), bundles natives, FTS5 available |

## Architecture

```
JadxService.load(path)
  ├─ sha256(file) → index file <indexDir>/<sha256>.db
  ├─ IndexStore.open()  (meta: jadxVersion + schemaVersion; mismatch → rebuild)
  ├─ background IndexBuilder thread:
  │     for each ClassNode (all, incl. inners) → for each MethodNode:
  │       mth.load(); walk insns; mth.unload()
  │       → rows: strings(method_id, value), xref_edges(src_method, dst_id, dst_type)
  │     state: building → ready (or disabled on failure)
  └─ ApkSession wires:
        IndexedCodeCache   (memory → SQLite sources table; delegates first decompile to jadx)
        IndexedSearchService (strings from SQLite; classes/methods unchanged in-memory)
        IndexedXrefService   (incoming: jadx usage info; outgoing: xref_edges)
        ResourceTableIndex   (arsc decoded once at load; no SQLite)
```

Until the build completes, every service falls back to the Phase-1
implementation — zero regression while indexing; tools never block on it.

## IndexStore schema

Single SQLite file per input content hash. WAL journal. Tables:

- `meta(key TEXT PRIMARY KEY, value TEXT)` — `jadx_version`, `schema_version`,
  `input_size`, `built_at`.
- `strings(seq INTEGER PRIMARY KEY, method_id TEXT, class_id TEXT, value TEXT)`
- `xref_edges(seq INTEGER PRIMARY KEY, src_method TEXT, dst_id TEXT, dst_type TEXT)`
- `sources(class_id TEXT PRIMARY KEY, source TEXT)`

Rebuild = write `<sha256>.db.tmp`, atomic rename. Existing DB with matching
jadx/schema version opens instantly (no rebuild).

String query path: `SELECT ... WHERE instr(value, ?) > 0` (case handling in
SQL via `instr(lower(value), lower(?))`). FTS5 trigram is an optional
accelerator only if present; correctness never depends on it.

## Tool API changes (all additive)

| Tool | Change |
|---|---|
| `search_strings` | each match gains `methodId` + `methodName`; no decompilation needed; `maxClasses` accepted but deprecated (ignored) |
| `get_xrefs` | response adds `outgoing`, `outgoingCount`, `outgoingTruncated` (class/method have outgoing; field → null) |
| `get_resource` | accepts optional `id` (`"0x7f0e0001"` or decimal) instead of `path`; value-only resources return `kind:"value"` + resolved text |
| `list_resources` | entries gain `id` (hex) when the resource table is available |
| `get_apk_info` | adds `index: {state, stringCount, edgeCount}` — state: `building` \| `ready` \| `disabled` |

`StringMatch.line` stays nullable (indexed results carry no line number).
`XrefService` interface changes `incoming(...)` → `xrefs(symbolType, symbolId,
limit)` returning both directions; `JadxXrefService` remains as the fallback.

## CLI

- `--index-dir <path>` — default `~/.jadx-mcp/index`
- `--no-index` — disables all persistence; pure Phase-1 behavior

Fat jar grows ~22 MB → ~35 MB (SQLite natives for all platforms; kept — the
tool is cross-platform by design).

## Failure handling

Any SQLite/disk error at open or build: log a warning, set `index.state =
disabled`, keep Phase-1 service implementations. No tool ever fails because of
the index. Session replace closes the DB handle of the old session.

## Files

New (`src/main/java/dev/jadxmcp/core/`): `IndexStore`, `IndexBuilder`,
`IndexedSearchService`, `IndexedXrefService`, `IndexedCodeCache`,
`ResourceTableIndex`.

Modified: `ApkSession` (wiring + resource table), `JadxService` (hash + index
lifecycle), `CliOptions`, `Main`, `SearchTools`, `XrefTools`, `ResourceTools`,
`ApkTools`, models `StringMatch`/`XrefInfo`/`ResourceEntryInfo`/
`ResourceContent`/`ApkInfo` (+`IndexState`), `build.gradle.kts`, README, AGENTS.

## Testing

- Fixture gains: string constants in a method, a second class invoking
  `CryptoUtil` (outgoing edge), keep lambda-free classes (dexed `--min-api 26`).
- `IndexStoreTest`: build → close → reopen (no rebuild), version bump →
  rebuild, corrupt dir → disabled fallback.
- `ToolRegistryTest`: outgoing xrefs present, `get_resource` by id, string
  search returns method context, deprecated `maxClasses` ignored.
- `JadxServiceTest`: source cache round-trip (decompile once, reload session,
  read from cache).
- ITs unchanged must stay green (`TransportConsistencyIT` covers additive
  fields). Verify with `test --rerun` + fresh `build/fixtures/test.apk`.

## Risks

- `mth.load()/unload()` at scale on real APKs — fixture proves correctness,
  not performance; if a real APK shows issues, builder can batch + yield.
- Substring queries < 3 chars can't use trigram index — degrade to table scan
  over pre-extracted strings (still no decompilation; bounded work).
- Unbounded index dir growth across many APKs — documented limitation;
  manual deletion is safe (rebuild on next load).
