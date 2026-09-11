package dev.jadxmcp.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed index for one input file: string constants, outgoing reference
 * edges and cached decompiled sources.
 *
 * <p>One database file per input content hash ({@code <sha256>.db}). The build
 * phase writes to {@code <sha256>.db.tmp}; {@link #markReady()} commits, moves
 * the file into place atomically-as-possible and reopens it. A database whose
 * {@code meta} matches the current jadx version opens instantly in
 * {@link Phase#READY} without rebuilding.
 *
 * <p>All access is synchronized on a single JDBC connection: the builder thread
 * writes while tool threads may read; correctness is guaranteed by the lock,
 * and {@link #awaitReady(long)} gates readers that need a complete index.
 * Any construction failure yields {@code null} from {@link #open} — callers
 * fall back to the Phase 1 in-memory services.
 */
public final class IndexStore implements AutoCloseable {

	public enum Phase {
		BUILDING, READY
	}

	/** One indexed string constant: where it appears (method) and its value. */
	public record StringRow(String classId, String className, String methodId, String methodName, String value) {
	}

	/** One outgoing reference edge: destination symbol id and its kind. */
	public record EdgeRow(String dstId, String dstType) {
	}

	private static final Logger LOG = LoggerFactory.getLogger(IndexStore.class);
	private static final String SCHEMA_VERSION = "1";

	private final String jadxVersion;
	private Connection conn;
	private volatile Phase phase = Phase.BUILDING;
	private long stringCount;
	private long edgeCount;

	private IndexStore(Connection conn, String jadxVersion, Phase phase, long strings, long edges) {
		this.conn = conn;
		this.jadxVersion = jadxVersion;
		this.phase = phase;
		this.stringCount = strings;
		this.edgeCount = edges;
	}

	/**
	 * Open (or prepare) the index for the given input hash. Returns {@code null}
	 * when the directory cannot be created or the database cannot be opened —
	 * never throws.
	 */
	public static IndexStore open(Path dir, String sha256, String jadxVersion) {
		if (dir == null || sha256 == null) {
			return null;
		}
		try {
			Files.createDirectories(dir);
		} catch (Exception e) {
			LOG.debug("index: cannot create index dir {}", dir, e);
			return null;
		}
		Path db = dir.resolve(sha256 + ".db");
		if (Files.exists(db)) {
			IndexStore existing = tryOpenExisting(db, jadxVersion);
			if (existing != null) {
				return existing;
			}
		}
		return openFresh(dir, sha256, jadxVersion);
	}

	private static IndexStore tryOpenExisting(Path db, String jadxVersion) {
		Connection c = null;
		try {
			c = connect(db);
			String schema = meta(c, "schema_version");
			String version = meta(c, "jadx_version");
			if (SCHEMA_VERSION.equals(schema) && jadxVersion.equals(version)) {
				long strings = parseOr(meta(c, "string_count"), 0L);
				long edges = parseOr(meta(c, "edge_count"), 0L);
				LOG.debug("index: reusing existing index {}", db.getFileName());
				return new IndexStore(c, jadxVersion, Phase.READY, strings, edges);
			}
		} catch (Exception e) {
			LOG.debug("index: existing db unusable, rebuilding: {}", db.getFileName(), e);
		}
		closeQuietly(c);
		return null;
	}

	private static IndexStore openFresh(Path dir, String sha256, String jadxVersion) {
		Path tmp = dir.resolve(sha256 + ".db.tmp");
		Connection c = null;
		try {
			Files.deleteIfExists(tmp);
			Files.deleteIfExists(dir.resolve(sha256 + ".db-wal"));
			Files.deleteIfExists(dir.resolve(sha256 + ".db.tmp-wal"));
			c = connect(tmp);
			try (Statement st = c.createStatement()) {
				st.executeUpdate("""
						CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
						CREATE TABLE strings(
							seq INTEGER PRIMARY KEY, class_id TEXT NOT NULL, class_name TEXT,
							method_id TEXT NOT NULL, method_name TEXT, value TEXT NOT NULL);
						CREATE TABLE xref_edges(
							seq INTEGER PRIMARY KEY, src_method TEXT NOT NULL,
							dst_id TEXT NOT NULL, dst_type TEXT NOT NULL);
						CREATE TABLE sources(
							class_id TEXT PRIMARY KEY, source TEXT NOT NULL);
						""");
			}
			c.setAutoCommit(false);
			return new IndexStore(c, jadxVersion, Phase.BUILDING, 0, 0);
		} catch (Exception e) {
			LOG.debug("index: cannot create fresh index for {}", sha256, e);
			closeQuietly(c);
			return null;
		}
	}

	private static Connection connect(Path file) throws SQLException {
		Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
		try (Statement st = c.createStatement()) {
			st.execute("PRAGMA journal_mode=WAL");
			st.execute("PRAGMA synchronous=NORMAL");
		}
		return c;
	}

	// ------------------------------------------------------------------ meta

	private static String meta(Connection c, String key) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
			ps.setString(1, key);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private static long parseOr(String value, long fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void putMeta(String key, String value) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)")) {
			ps.setString(1, key);
			ps.setString(2, value);
			ps.executeUpdate();
		}
	}

	// ---------------------------------------------------------------- writes

	/** Insert string rows (one call per method or class batch). */
	public synchronized void insertStrings(List<StringRow> rows) {
		if (phase != Phase.BUILDING || rows.isEmpty()) {
			return;
		}
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO strings(class_id, class_name, method_id, method_name, value) VALUES(?,?,?,?,?)")) {
			for (StringRow r : rows) {
				ps.setString(1, r.classId());
				ps.setString(2, r.className());
				ps.setString(3, r.methodId());
				ps.setString(4, r.methodName());
				ps.setString(5, r.value());
				ps.addBatch();
			}
			ps.executeBatch();
			conn.commit();
			stringCount += rows.size();
		} catch (SQLException e) {
			rollback();
			LOG.debug("index: insertStrings failed", e);
		}
	}

	/** Insert outgoing edges of one source method (duplicates across calls are fine). */
	public synchronized void insertEdges(String srcMethod, List<EdgeRow> edges) {
		if (phase != Phase.BUILDING || edges.isEmpty()) {
			return;
		}
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO xref_edges(src_method, dst_id, dst_type) VALUES(?,?,?)")) {
			for (EdgeRow e : edges) {
				ps.setString(1, srcMethod);
				ps.setString(2, e.dstId());
				ps.setString(3, e.dstType());
				ps.addBatch();
			}
			ps.executeBatch();
			conn.commit();
			edgeCount += edges.size();
		} catch (SQLException e) {
			rollback();
			LOG.debug("index: insertEdges failed", e);
		}
	}

	/** Persist a decompiled class source (INSERT OR REPLACE). */
	public synchronized void putSource(String classId, String source) {
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT OR REPLACE INTO sources(class_id, source) VALUES(?,?)")) {
			ps.setString(1, classId);
			ps.setString(2, source);
			ps.executeUpdate();
			conn.commit();
		} catch (SQLException e) {
			rollback();
			LOG.debug("index: putSource failed for {}", classId, e);
		}
	}

	// ----------------------------------------------------------------- reads

	public synchronized String cachedSource(String classId) {
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT source FROM sources WHERE class_id = ?")) {
			ps.setString(1, classId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		} catch (SQLException e) {
			return null;
		}
	}

	/** Substring search over indexed strings. Case-insensitive unless requested. */
	public synchronized List<StringRow> searchStrings(String query, boolean caseSensitive, int limit) {
		String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
		String sql = caseSensitive
				? "SELECT class_id, class_name, method_id, method_name, value FROM strings"
						+ " WHERE instr(value, ?) > 0 ORDER BY seq LIMIT ?"
				: "SELECT class_id, class_name, method_id, method_name, value FROM strings"
						+ " WHERE instr(lower(value), ?) > 0 ORDER BY seq LIMIT ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, needle);
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				List<StringRow> out = new ArrayList<>();
				while (rs.next()) {
					out.add(new StringRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
							rs.getString(5)));
				}
				return out;
			}
		} catch (SQLException e) {
			LOG.debug("index: searchStrings failed", e);
			return List.of();
		}
	}

	/** Distinct outgoing destinations of a method (max+1 rows for truncation detection). */
	public synchronized List<EdgeRow> outgoingOfMethod(String methodId, int max) {
		return queryEdges("SELECT DISTINCT dst_id, dst_type FROM xref_edges WHERE src_method = ?"
				+ " ORDER BY dst_id LIMIT " + (max + 1), methodId);
	}

	/** Distinct outgoing destinations of all methods of one class (own methods, no inners). */
	public synchronized List<EdgeRow> outgoingOfClass(String classId, int max) {
		return queryEdges("SELECT DISTINCT dst_id, dst_type FROM xref_edges WHERE src_method LIKE ? ESCAPE '\\'"
				+ " ORDER BY dst_id LIMIT " + (max + 1), escapeLike(classId) + "->%");
	}

	private List<EdgeRow> queryEdges(String sql, String param) {
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, param);
			try (ResultSet rs = ps.executeQuery()) {
				List<EdgeRow> out = new ArrayList<>();
				while (rs.next()) {
					out.add(new EdgeRow(rs.getString(1), rs.getString(2)));
				}
				return out;
			}
		} catch (SQLException e) {
			LOG.debug("index: edge query failed", e);
			return List.of();
		}
	}

	private static String escapeLike(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	// ------------------------------------------------------------- lifecycle

	public boolean needsBuild() {
		return phase == Phase.BUILDING;
	}

	public Phase phase() {
		return phase;
	}

	public boolean ready() {
		return phase == Phase.READY;
	}

	/**
	 * Finish the build: write meta, commit, move the tmp database into place and
	 * reopen it. Idempotent for an already-ready store.
	 */
	public synchronized void markReady() {
		if (phase == Phase.READY) {
			return;
		}
		try {
			putMeta("schema_version", SCHEMA_VERSION);
			putMeta("jadx_version", jadxVersion);
			putMeta("string_count", Long.toString(stringCount));
			putMeta("edge_count", Long.toString(edgeCount));
			putMeta("built_at", Instant.now().toString());
			conn.commit();
		} catch (SQLException e) {
			rollback();
			LOG.warn("index: markReady commit failed; index stays building (tools fall back)", e);
			return;
		}
		String file;
		try {
			file = conn.getMetaData().getURL().replaceFirst("^jdbc:sqlite:", "");
			conn.close();
		} catch (SQLException e) {
			LOG.warn("index: close for swap failed", e);
			return;
		}
		try {
			Path tmp = Path.of(file);
			Path db = Path.of(file.substring(0, file.length() - 4)); // strip ".tmp"
			Files.deleteIfExists(Path.of(db + "-wal"));
			Files.deleteIfExists(Path.of(db + "-shm"));
			Files.deleteIfExists(Path.of(file + "-wal"));
			Files.deleteIfExists(Path.of(file + "-shm"));
			Files.move(tmp, db, StandardCopyOption.REPLACE_EXISTING);
			conn = connect(db);
			conn.setAutoCommit(true);
			phase = Phase.READY;
			LOG.info("index ready: {} strings, {} edges", stringCount, edgeCount);
		} catch (Exception e) {
			LOG.warn("index: finalize move failed; index stays building (tools fall back)", e);
			closeQuietly(conn);
			conn = null;
		}
	}

	/** Wait until {@link Phase#READY} or timeout (ms). Returns final readiness. */
	public boolean awaitReady(long timeoutMillis) throws InterruptedException {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		while (!ready()) {
			if (System.nanoTime() - deadline >= 0) {
				return false;
			}
			Thread.sleep(25);
		}
		return true;
	}

	public long stringCount() {
		return stringCount;
	}

	public long edgeCount() {
		return edgeCount;
	}

	@Override
	public synchronized void close() {
		closeQuietly(conn);
		conn = null;
	}

	private void rollback() {
		try {
			conn.rollback();
		} catch (SQLException ignored) {
			// already closed or broken; nothing to recover
		}
	}

	private static void closeQuietly(Connection c) {
		if (c != null) {
			try {
				c.close();
			} catch (SQLException ignored) {
				// best effort
			}
		}
	}
}
