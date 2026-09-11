package dev.jadxmcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import dev.jadxmcp.core.IndexConfig;

class CliOptionsTest {

	@Test
	void defaultIsEnabledWithDefaultDir() {
		CliOptions opts = CliOptions.parse(new String[] { "stdio" });
		IndexConfig cfg = opts.indexConfig();
		assertTrue(cfg.enabled());
		assertEquals(IndexConfig.defaultDir(), cfg.dir());
	}

	@Test
	void noIndexDisables() {
		CliOptions opts = CliOptions.parse(new String[] { "stdio", "--no-index" });
		IndexConfig cfg = opts.indexConfig();
		assertFalse(cfg.enabled());
	}

	@Test
	void indexDirOverrides() {
		CliOptions opts = CliOptions.parse(new String[] { "server", "--index-dir", "/tmp/jadx-idx", "--port", "1" });
		IndexConfig cfg = opts.indexConfig();
		assertTrue(cfg.enabled());
		assertEquals(Path.of("/tmp/jadx-idx"), cfg.dir());
	}

	@Test
	void bothIndexFlagsRejected() {
		CliOptions.CliException e = assertThrows(CliOptions.CliException.class,
				() -> CliOptions.parse(new String[] { "stdio", "--no-index", "--index-dir", "/tmp/x" }));
		assertNotNull(e.getMessage());
	}

	@Test
	void unknownFlagStillRejected() {
		assertThrows(CliOptions.CliException.class,
				() -> CliOptions.parse(new String[] { "stdio", "--wat" }));
	}
}
