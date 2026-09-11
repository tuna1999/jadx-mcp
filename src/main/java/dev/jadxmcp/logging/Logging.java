package dev.jadxmcp.logging;

/**
 * Logging configuration. Must run before any class that touches SLF4J.
 *
 * <p>STDIO mode requires stdout to carry MCP protocol traffic only, so all log
 * output is forced to stderr (slf4j-simple already defaults to stderr, we make it
 * explicit and configurable by level).
 */
public final class Logging {

	static {
		// Defensive defaults: never write logs to a file, never touch stdout.
		System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
	}

	private Logging() {
	}

	/** Configure level: trace|debug|info|warn|error. */
	public static void init(String level) {
		String lvl = level == null ? "info" : level.toLowerCase();
		switch (lvl) {
			case "trace":
			case "debug":
			case "info":
			case "warn":
			case "error":
				break;
			default:
				lvl = "info";
		}
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", lvl);
		System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
		System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
		System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
	}

}
