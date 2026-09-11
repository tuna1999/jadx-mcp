package dev.jadxmcp.transport;

import java.net.InetSocketAddress;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.tools.ToolRegistry;
import dev.jadxmcp.util.Version;

/**
 * HTTP MCP transport: Streamable HTTP at {@code /mcp} served by embedded Jetty.
 * Same {@link ToolRegistry} and business logic as STDIO; this class contains no
 * JADX code. Binds to 127.0.0.1 unless an explicit --host is given.
 */
public final class HttpMcpServer implements AutoCloseable {

	public static final String MCP_ENDPOINT = "/mcp";

	private static final Logger LOG = LoggerFactory.getLogger(HttpMcpServer.class);

	private final ToolRegistry registry;
	private final String host;
	private final int port;
	private final Authenticator authenticator;

	private Server jetty;
	private McpSyncServer mcpServer;
	private HttpServletStreamableServerTransportProvider provider;

	public HttpMcpServer(ToolRegistry registry, String host, int port, Authenticator authenticator) {
		this.registry = registry;
		this.host = host;
		this.port = port;
		this.authenticator = authenticator;
	}

	public HttpMcpServer(JadxService jadx, String host, int port) {
		this(new ToolRegistry(jadx), host, port, null);
	}

	public synchronized void start() throws Exception {
		provider = HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(McpJsonDefaults.getMapper())
				.mcpEndpoint(MCP_ENDPOINT)
				.build();
		mcpServer = McpServers.build(provider, registry);

		jetty = new Server(new InetSocketAddress(host, port));
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		HttpServlet servlet = authenticator == null
				? provider
				: new AuthenticatedServlet(provider, authenticator);
		ServletHolder holder = new ServletHolder(servlet);
		holder.setAsyncSupported(true);
		context.addServlet(holder, MCP_ENDPOINT);
		jetty.setHandler(context);
		jetty.start();

		if (!isLoopback(host)) {
			LOG.warn("jadx-mcp is bound to {} - it is reachable from the network", host);
		}
		LOG.info("jadx-mcp {} streamable HTTP listening on http://{}:{}{}", Version.value(), host, port,
				MCP_ENDPOINT);
	}

	private static boolean isLoopback(String host) {
		return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
	}


	/** Actual bound port (useful when constructed with port 0). */
	public int port() {
		return jetty.getURI().getPort();
	}
	public String endpointUrl() {
		return "http://" + host + ":" + port + MCP_ENDPOINT;
	}

	/** Block until the HTTP server stops. */
	public void awaitStop() throws InterruptedException {
		Server s = jetty;
		if (s != null) {
			s.join();
		}
	}

	@Override
	public synchronized void close() throws Exception {
		Exception first = null;
		try {
			if (jetty != null) {
				jetty.stop();
			}
		} catch (Exception e) {
			first = e;
		}
		try {
			if (mcpServer != null) {
				mcpServer.close();
			}
		} catch (Exception e) {
			if (first == null) {
				first = e;
			}
		}
		if (first != null) {
			throw first;
		}
		LOG.info("HTTP transport stopped");
	}

	/** Phase 1 auth wiring: rejects unauthenticated requests before MCP handling. */
	static final class AuthenticatedServlet extends HttpServlet {
		private final HttpServlet delegate;
		private final Authenticator authenticator;

		AuthenticatedServlet(HttpServlet delegate, Authenticator authenticator) {
			this.delegate = delegate;
			this.authenticator = authenticator;
		}

		@Override
		public void service(ServletRequest req, ServletResponse res) throws jakarta.servlet.ServletException,
				java.io.IOException {
			if (req instanceof HttpServletRequest httpReq && res instanceof HttpServletResponse httpRes) {
				if (!authenticator.authenticate(httpReq)) {
					httpRes.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
					httpRes.setContentType("application/json");
					httpRes.getWriter().write("{\"error\":\"unauthorized\"}");
					return;
				}
			}
			delegate.service(req, res);
		}
	}
}
