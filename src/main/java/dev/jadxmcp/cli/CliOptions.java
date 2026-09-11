package dev.jadxmcp.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed command line for jadx-mcp.
 *
 * <pre>
 *   jadx-mcp stdio  [--input &lt;path&gt;] [--log-level &lt;level&gt;]
 *   jadx-mcp server [--input &lt;path&gt;] [--host &lt;host&gt;] [--port &lt;port&gt;] [--log-level &lt;level&gt;]
 * </pre>
 */
public final class CliOptions {

	public enum Mode {
		STDIO,
		SERVER
	}

	private final Mode mode;
	private final Path input;
	private final String host;
	private final int port;
	private final String logLevel;

	private CliOptions(Mode mode, Path input, String host, int port, String logLevel) {
		this.mode = mode;
		this.input = input;
		this.host = host;
		this.port = port;
		this.logLevel = logLevel;
	}

	public static CliOptions parse(String[] args) {
		if (args.length == 0) {
			throw new CliException("missing mode argument; expected 'stdio' or 'server'");
		}
		Mode mode;
		switch (args[0]) {
			case "stdio":
				mode = Mode.STDIO;
				break;
			case "server":
				mode = Mode.SERVER;
				break;
			case "-h":
			case "--help":
			case "help":
				throw new CliException(usage(), false, true);
			case "-V":
			case "--version":
			case "version":
				throw new CliException(dev.jadxmcp.util.Version.value(), false, true);
			default:
				throw new CliException("unknown mode '" + args[0] + "'; expected 'stdio' or 'server'");
		}
		Path input = null;
		String host = "127.0.0.1";
		int port = 8650;
		String logLevel = "info";
		List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));
		for (int i = 0; i < rest.size(); i++) {
			String a = rest.get(i);
			switch (a) {
				case "--input":
					input = Path.of(requireValue(rest, ++i, a));
					break;
				case "--host":
					host = requireValue(rest, ++i, a);
					break;
				case "--port":
					String portStr = requireValue(rest, ++i, a);
					try {
						port = Integer.parseInt(portStr);
					} catch (NumberFormatException e) {
						throw new CliException("invalid --port value '" + portStr + "'");
					}
					if (port <= 0 || port > 65535) {
						throw new CliException("port out of range: " + port);
					}
					break;
				case "--log-level":
					logLevel = requireValue(rest, ++i, a).toLowerCase();
					if (!List.of("trace", "debug", "info", "warn", "error").contains(logLevel)) {
						throw new CliException("invalid --log-level '" + logLevel + "'");
					}
					break;
				default:
					throw new CliException("unknown argument '" + a + "'");
			}
		}
		return new CliOptions(mode, input, host, port, logLevel);
	}

	private static String requireValue(List<String> args, int index, String flag) {
		if (index >= args.size()) {
			throw new CliException("missing value for " + flag);
		}
		return args.get(index);
	}

	public Mode mode() {
		return mode;
	}

	/** Input file or {@code null} when the client will call load_apk later. */
	public Path input() {
		return input;
	}
	public String host() {
		return host;
	}

	public int port() {
		return port;
	}

	public String logLevel() {
		return logLevel;
	}

	public boolean bindsNonLoopback() {
		return !"127.0.0.1".equals(host) && !"localhost".equals(host) && !"::1".equals(host);
	}

	public static String usage() {
		return """
				jadx-mcp - JADX MCP server for AI agents

				Usage:
				  jadx-mcp stdio  [--input <apk|dex|jar>] [--log-level <trace|debug|info|warn|error>]
				  jadx-mcp server [--input <apk|dex|jar>] [--host <host>] [--port <port>] [--log-level <level>]

				Modes:
				  stdio   Headless MCP server speaking newline-delimited JSON-RPC over stdin/stdout.
				          Logs go to stderr. Intended for Claude Code, Codex, Pi, ...
				  server  MCP server exposing the Streamable HTTP transport at http://<host>:<port>/mcp.

				Notes:
				  --host defaults to 127.0.0.1. Binding to anything else (e.g. 0.0.0.0) is explicit
				  and exposes the server to the network.
				  If --input is omitted, load the APK later via the load_apk tool.""";
	}

	public static final class CliException extends RuntimeException {
		private final boolean usage;
		private final boolean help;

		public CliException(String message) {
			this(message, true, false);
		}

		public CliException(String message, boolean usage) {
			this(message, usage, false);
		}

		public CliException(String message, boolean usage, boolean help) {
			super(message);
			this.usage = usage;
			this.help = help;
		}

		public boolean printUsage() {
			return usage;
		}

		public boolean isHelp() {
			return help;
		}
	}
}
