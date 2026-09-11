package dev.jadxmcp.core;

import java.nio.file.Path;

/**
 * Index persistence configuration: enable/disable plus the directory that holds
 * one SQLite database per input content hash.
 */
public record IndexConfig(boolean enabled, Path dir) {

	/** Default location: {@code ~/.jadx-mcp/index}. */
	public static Path defaultDir() {
		return Path.of(System.getProperty("user.home"), ".jadx-mcp", "index");
	}

	public static IndexConfig enabledDefault() {
		return new IndexConfig(true, defaultDir());
	}

	public static IndexConfig disabled() {
		return new IndexConfig(false, null);
	}

	public static IndexConfig of(Path dir) {
		return new IndexConfig(true, dir);
	}
}
