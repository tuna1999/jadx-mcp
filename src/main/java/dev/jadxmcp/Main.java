package dev.jadxmcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jadxmcp.cli.CliOptions;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.logging.Logging;
import dev.jadxmcp.transport.HttpMcpServer;
import dev.jadxmcp.transport.StdioMcpServer;

/** Entry point: {@code jadx-mcp stdio|server [options]}. */
public final class Main {

	public static void main(String[] args) {
		CliOptions opts;
		try {
			opts = CliOptions.parse(args);
		} catch (CliOptions.CliException e) {
			if (e.isHelp()) {
				System.err.println(e.getMessage());
				System.exit(0);
				return;
			}
			System.err.println("jadx-mcp: " + e.getMessage());
			if (e.printUsage()) {
				System.err.println();
				System.err.println(CliOptions.usage());
			}
			System.exit(2);
			return;
		}

		Logging.init(opts.logLevel());
		Logger log = LoggerFactory.getLogger(Main.class);

		try (JadxService jadx = new JadxService(opts.indexConfig())) {
			if (opts.input() != null) {
				try {
					jadx.load(opts.input());
				} catch (Exception e) {
					log.error("startup --input failed: {}", String.valueOf(e.getMessage()));
					System.exit(3);
					return;
				}
			}
			switch (opts.mode()) {
				case STDIO -> {
					log.info("starting stdio mode{}", opts.input() == null ? " (no input preloaded)" : "");
					new StdioMcpServer(jadx).run();
					System.exit(0);
				}
				case SERVER -> {
					try (HttpMcpServer http = new HttpMcpServer(jadx, opts.host(), opts.port())) {
						http.start();
						http.awaitStop();
					}
					System.exit(0);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("interrupted");
			System.exit(130);
		} catch (Exception e) {
			log.error("fatal error", e);
			System.exit(1);
		}
	}

	private Main() {
	}
}
