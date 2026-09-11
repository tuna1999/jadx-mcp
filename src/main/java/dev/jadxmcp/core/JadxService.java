package dev.jadxmcp.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import dev.jadxmcp.model.ErrorCode;

/**
 * Owns the active {@link ApkSession}: validation, loading, replacement and
 * shutdown of jadx resources. All MCP tools go through this service.
 */
public final class JadxService implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(JadxService.class);

	private volatile ApkSession session;

	/** Load (or replace) the active input. Returns the new session. */
	public synchronized ApkSession load(Path path) {
		if (path == null) {
			throw new JadxServiceException(ErrorCode.INVALID_ARGUMENT, "path must not be null");
		}
		Path normalized = path.toAbsolutePath().normalize();
		if (!Files.exists(normalized)) {
			throw new JadxServiceException(ErrorCode.FILE_NOT_FOUND,
					"file not found: " + normalized);
		}
		if (!Files.isRegularFile(normalized)) {
			throw new JadxServiceException(ErrorCode.INVALID_INPUT_FILE,
					"not a regular file: " + normalized);
		}
		if (!looksSupported(normalized)) {
			throw new JadxServiceException(ErrorCode.INVALID_INPUT_FILE,
					"unsupported input file (expected APK/DEX/JAR/ZIP): " + normalized);
		}
		long size;
		try {
			size = Files.size(normalized);
		} catch (IOException e) {
			throw new JadxServiceException(ErrorCode.INVALID_INPUT_FILE,
					"cannot stat file: " + normalized, e);
		}

		ApkSession old = this.session;
		ApkSession newSession;
		try {
			JadxArgs args = new JadxArgs();
			args.setInputFile(normalized.toFile());
			args.setThreadsCount(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1)));
			JadxDecompiler jadx = new JadxDecompiler(args);
			jadx.load();
			// force resource loading so counts and manifest are available immediately
			jadx.getResources();
			newSession = new ApkSession(jadx, normalized, size);
		} catch (Exception e) {
			LOG.error("Failed to load input '{}'", normalized, e);
			throw new JadxServiceException(ErrorCode.INVALID_INPUT_FILE,
					"jadx failed to load input: " + e.getMessage(), e);
		}
		this.session = newSession;
		LOG.info("Loaded {} ({} top-level classes, {} resources)",
				normalized, newSession.topLevelClassCount(), newSession.resources().size());
		if (old != null) {
			closeQuietly(old);
			LOG.info("Previous input unloaded");
		}
		return newSession;
	}

	private static boolean looksSupported(Path file) {
		String name = file.getFileName().toString().toLowerCase();
		if (name.endsWith(".dex")) {
			return hasMagic(file, "dex\n".getBytes(), 0);
		}
		if (name.endsWith(".class")) {
			return hasMagic(file, new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE }, 0);
		}
		// APK / JAR / ZIP / AAR
		return hasMagic(file, new byte[] { 'P', 'K', 0x03, 0x04 }, 0)
				|| hasMagic(file, new byte[] { 'P', 'K', 0x05, 0x06 }, 0);
	}

	private static boolean hasMagic(Path file, byte[] magic, int offset) {
		byte[] buf = new byte[offset + magic.length];
		try (InputStream in = Files.newInputStream(file)) {
			int read = 0;
			while (read < buf.length) {
				int n = in.read(buf, read, buf.length - read);
				if (n < 0) {
					return false;
				}
				read += n;
			}
		} catch (IOException e) {
			return false;
		}
		for (int i = 0; i < magic.length; i++) {
			if (buf[offset + i] != magic[i]) {
				return false;
			}
		}
		return true;
	}

	public Optional<ApkSession> session() {
		return Optional.ofNullable(session);
	}

	/** Session or {@link JadxServiceException} with {@link ErrorCode#NO_APK_LOADED}. */
	public ApkSession requireSession() {
		ApkSession s = session;
		if (s == null) {
			throw new JadxServiceException(ErrorCode.NO_APK_LOADED,
					"no APK loaded; call load_apk first");
		}
		return s;
	}

	private static void closeQuietly(ApkSession s) {
		try {
			s.close();
		} catch (Exception e) {
			LOG.warn("Failed to close previous session", e);
		}
	}

	@Override
	public synchronized void close() {
		ApkSession s = session;
		session = null;
		if (s != null) {
			closeQuietly(s);
		}
	}

	/** Carries a machine-readable {@link ErrorCode}. */
	public static final class JadxServiceException extends RuntimeException {
		private final ErrorCode code;

		public JadxServiceException(ErrorCode code, String message) {
			this(code, message, null);
		}

		public JadxServiceException(ErrorCode code, String message, Throwable cause) {
			super(message, cause);
			this.code = code;
		}

		public ErrorCode code() {
			return code;
		}
	}
}
