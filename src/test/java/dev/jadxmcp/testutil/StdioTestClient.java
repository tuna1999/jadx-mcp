package dev.jadxmcp.testutil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.tools.ToolRegistry;
import dev.jadxmcp.transport.McpServers;

/**
 * In-JVM STDIO MCP client: drives the real SDK stdio transport through pipes.
 * Used for transport consistency tests without spawning a process.
 */
public final class StdioTestClient implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final PipedOutputStream toServer;
	private final BlockingQueue<JsonNode> inbound = new LinkedBlockingQueue<>();
	private volatile boolean closed;

	private StdioTestClient(PipedOutputStream toServer) {
		this.toServer = toServer;
	}

	/** Starts an MCP server over piped stdio around the given service. */
	public static StdioTestClient start(JadxService jadx) throws IOException {
		PipedOutputStream clientWrites = new PipedOutputStream();
		PipedInputStream serverIn = new PipedInputStream(clientWrites, 1 << 16);
		PipedInputStream clientReads = new PipedInputStream(1 << 16);
		PipedOutputStream serverOut = new PipedOutputStream(clientReads);

		StdioServerTransportProvider provider = new StdioServerTransportProvider(
				McpJsonDefaults.getMapper(), serverIn, serverOut);
		McpServers.build(provider, new ToolRegistry(jadx));

		StdioTestClient client = new StdioTestClient(clientWrites);
		Thread reader = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(clientReads, StandardCharsets.UTF_8))) {
				String line;
				while (!client.closed && (line = r.readLine()) != null) {
					if (line.isBlank()) {
						continue;
					}
					client.inbound.add(JSON.readTree(line));
				}
			} catch (IOException e) {
				// pipe closed during shutdown
			}
		}, "stdio-test-client-reader");
		reader.setDaemon(true);
		reader.start();
		return client;
	}

	/** Sends a request and waits for the response with the matching id. */
	public JsonNode request(long id, String method, String paramsJson) throws IOException, InterruptedException {
		send(id >= 0 ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":"
				+ (paramsJson == null ? "{}" : paramsJson) + "}"
				: "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":"
						+ (paramsJson == null ? "{}" : paramsJson) + "}");
		if (id < 0) {
			return null; // notification: no response expected
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
		while (System.nanoTime() < deadline) {
			JsonNode msg = inbound.poll(1, TimeUnit.SECONDS);
			if (msg == null) {
				continue;
			}
			if (msg.has("id") && msg.get("id").asLong() == id) {
				return msg;
			}
		}
		throw new IllegalStateException("no response for request id " + id + " (method " + method + ")");
	}

	public void send(String jsonLine) throws IOException {
		toServer.write((jsonLine + "\n").getBytes(StandardCharsets.UTF_8));
		toServer.flush();
	}

	@Override
	public void close() throws IOException {
		closed = true;
		toServer.close();
	}
}
