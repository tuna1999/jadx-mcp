package dev.jadxmcp.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.tools.ToolRegistry;
import dev.jadxmcp.util.Version;

/**
 * STDIO MCP transport. STDOUT carries MCP JSON-RPC only; every log line goes to
 * STDERR. Exits deterministically when the client closes stdin.
 */
public final class StdioMcpServer {

	private static final Logger LOG = LoggerFactory.getLogger(StdioMcpServer.class);

	private final ToolRegistry registry;

	public StdioMcpServer(ToolRegistry registry) {
		this.registry = registry;
	}

	public StdioMcpServer(JadxService jadx) {
		this(new ToolRegistry(jadx));
	}

	/** Blocks until the client closes stdin or the process is signalled. */
	public void run() {
		CountDownLatch done = new CountDownLatch(1);
		InputStream stdin = new EofNotifier(System.in, done::countDown);
		OutputStream stdout = System.out;

		McpServerTransportProvider provider = new StdioServerTransportProvider(
				McpJsonDefaults.getMapper(), stdin, stdout);
		McpSyncServer server = McpServers.build(provider, registry);

		Thread shutdownHook = new Thread(() -> {
			LOG.info("shutting down");
			done.countDown();
		}, "jadx-mcp-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		LOG.info("jadx-mcp {} stdio transport ready (logs on stderr)", Version.value());
		try {
			done.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		// stdin closed (or signal): let in-flight tool calls finish before closing
		try {
			if (!registry.awaitIdle(60_000)) {
				LOG.warn("shutdown with tool calls still in flight");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		try {
			server.close();
		} catch (Exception e) {
			LOG.warn("error closing MCP server", e);
		}
		LOG.info("stdio transport stopped");
	}

	/** Signals EOF via callback; single-threaded passthrough otherwise. */
	private static final class EofNotifier extends InputStream {
		private final InputStream delegate;
		private final Runnable onEof;
		private boolean eofSignalled;

		EofNotifier(InputStream delegate, Runnable onEof) {
			this.delegate = delegate;
			this.onEof = onEof;
		}

		@Override
		public int read() throws IOException {
			int r = delegate.read();
			if (r == -1) {
				signalEof();
			}
			return r;
		}

		@Override
		public int read(byte[] b) throws IOException {
			int n = delegate.read(b);
			if (n == -1) {
				signalEof();
			}
			return n;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int n = delegate.read(b, off, len);
			if (n == -1) {
				signalEof();
			}
			return n;
		}

		@Override
		public long skip(long n) throws IOException {
			return delegate.skip(n);
		}

		@Override
		public int available() throws IOException {
			return delegate.available();
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}

		private void signalEof() {
			if (!eofSignalled) {
				eofSignalled = true;
				onEof.run();
			}
		}
	}
}
