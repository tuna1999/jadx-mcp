package dev.jadxmcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.jadxmcp.core.IndexConfig;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.fixture.FixtureApk;
import dev.jadxmcp.testutil.HttpMcpClient;
import dev.jadxmcp.testutil.StdioTestClient;
import dev.jadxmcp.tools.ToolRegistry;
import tools.jackson.databind.JsonNode;

/**
 * Calls the same tools through the real STDIO and HTTP transports and asserts
 * the structured results are semantically identical.
 */
class TransportConsistencyIT {

	private static final String INIT = "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"it\",\"version\":\"1.0\"}}";

	private JadxService jadxForStdio;
	private JadxService jadxForHttp;
	private HttpMcpServer httpServer;
	private StdioTestClient stdio;
	private HttpMcpClient httpClient;

	@BeforeAll
	static void apk() {
		FixtureApk.apk();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (stdio != null) {
			stdio.close();
		}
		if (httpClient != null) {
			httpClient.close();
		}
		if (httpServer != null) {
			httpServer.close();
		}
		if (jadxForStdio != null) {
			jadxForStdio.close();
		}
		if (jadxForHttp != null) {
			jadxForHttp.close();
		}
	}

	private void initStdio() throws Exception {
		jadxForStdio = new JadxService(IndexConfig.disabled());
		jadxForStdio.load(FixtureApk.apk());
		stdio = StdioTestClient.start(jadxForStdio);
		stdio.request(1, "initialize", INIT);
		stdio.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
	}

	private void initHttp() throws Exception {
		jadxForHttp = new JadxService(IndexConfig.disabled());
		jadxForHttp.load(FixtureApk.apk());
		httpServer = new HttpMcpServer(jadxForHttp, "127.0.0.1", 0);
		httpServer.start();
		httpClient = new HttpMcpClient("http://127.0.0.1:" + httpServer.port() + "/mcp");
		httpClient.request(1, "initialize", INIT);
		httpClient.notify("notifications/initialized", null);
	}

	private JsonNode callBoth(String tool, String argsJson) throws Exception {
		JsonNode stdioResult = stdio.request(10, "tools/call",
				"{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}").get("result");
		JsonNode httpResult = httpClient.request(11, "tools/call",
				"{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}").get("result");
		assertEquals(stdioResult.get("isError").asBoolean(), httpResult.get("isError").asBoolean(),
				tool + ": isError mismatch");
		assertEquals(stable(stdioResult.get("structuredContent")), stable(httpResult.get("structuredContent")),
				tool + ": structured payload mismatch");
		return stdioResult;
	}

	@Test
	@Timeout(300)
	void sameToolsYieldSamePayloads() throws Exception {
		initStdio();
		initHttp();

		callBoth("get_apk_info", "{}");
		callBoth("list_classes", "{\"limit\":3}");
		callBoth("get_class_outline", "{\"class\":\"Ldev/jadxmcp/fixture/CryptoUtil;\"}");
		callBoth("get_method_source",
				"{\"method\":\"Ldev/jadxmcp/fixture/CryptoUtil;->encode([B[B)[B\"}");
		callBoth("search_strings", "{\"query\":\"api.fixture.example.com\"}");
		callBoth("get_xrefs",
				"{\"symbolType\":\"method\",\"id\":\"Ldev/jadxmcp/fixture/CryptoUtil;->encode([B[B)[B\"}");
		callBoth("get_manifest", "{}");
		callBoth("list_resources", "{}");
	}

	/** Removes volatile fields (timestamps) so payloads compare deterministically. */
	private static tools.jackson.databind.JsonNode stable(tools.jackson.databind.JsonNode node) {
		if (node != null && node.isObject() && node.has("loadedAt")) {
			var copy = ((tools.jackson.databind.node.ObjectNode) node).deepCopy();
			copy.remove("loadedAt");
			return copy;
		}
		return node;
	}

	@Test
	@Timeout(300)
	void errorsAreEquivalentAcrossTransports() throws Exception {
		initStdio();
		initHttp();
		JsonNode result = callBoth("get_class_source", "{\"class\":\"Lno/such/Class;\"}");
		assertEquals(true, result.get("isError").asBoolean());
		assertEquals("CLASS_NOT_FOUND", result.get("structuredContent").get("code").asString());
	}
}
