package dev.jadxmcp.testutil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal MCP Streamable HTTP test client on top of the JDK HttpClient.
 * Handles both plain JSON and SSE-framed responses.
 */
public final class HttpMcpClient implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient http;
	private final String endpoint;
	private volatile String sessionId;

	public HttpMcpClient(String endpoint) {
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		this.endpoint = endpoint;
	}

	public JsonNode request(long id, String method, String paramsJson)
			throws IOException, InterruptedException {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":"
				+ (paramsJson == null ? "{}" : paramsJson) + "}";
		HttpResponse<String> resp = post(body);
		String newSession = resp.headers().firstValue("mcp-session-id").orElse(null);
		if (newSession != null) {
			this.sessionId = newSession;
		}
		return extractResponse(resp, id);
	}

	public void notify(String method, String paramsJson) throws IOException, InterruptedException {
		String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":"
				+ (paramsJson == null ? "{}" : paramsJson) + "}";
		post(body);
	}

	private HttpResponse<String> post(String body) throws IOException, InterruptedException {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
				.timeout(Duration.ofSeconds(120))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (sessionId != null) {
			b.header("Mcp-Session-Id", sessionId);
		}
		HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() >= 400) {
			throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
		}
		return resp;
	}

	private JsonNode extractResponse(HttpResponse<String> resp, long id) {
		String contentType = resp.headers().firstValue("content-type").orElse("");
		if (contentType.contains("text/event-stream")) {
			// SSE frames: take the last data line carrying our id
			String data = null;
			for (String line : resp.body().split("\n")) {
				if (line.startsWith("data:")) {
					String payload = line.substring(5).trim();
					JsonNode node = JSON.readTree(payload);
					if (node.has("id") && node.get("id").asLong() == id) {
						data = payload;
					}
				}
			}
			if (data == null) {
				throw new IllegalStateException("no SSE frame with id " + id);
			}
			return JSON.readTree(data);
		}
		return JSON.readTree(resp.body());
	}

	public static List<String> sseDataLines(String body) {
		return body.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5).trim()).toList();
	}

	@Override
	public void close() {
		// HttpClient needs no explicit close on JDK 17+ in this usage
	}
}
