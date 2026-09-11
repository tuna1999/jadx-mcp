package dev.jadxmcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.jadxmcp.core.IndexConfig;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.fixture.FixtureApk;
import dev.jadxmcp.testutil.HttpMcpClient;
import dev.jadxmcp.tools.ToolRegistry;
import tools.jackson.databind.JsonNode;

/** Streamable HTTP transport against an in-JVM Jetty server. */
class HttpMcpServerIT {

	private JadxService jadx;
	private HttpMcpServer server;
	private HttpMcpClient client;

	@BeforeAll
	static void apk() {
		FixtureApk.apk();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (client != null) {
			client.close();
		}
		if (server != null) {
			server.close();
		}
		if (jadx != null) {
			jadx.close();
		}
	}

	private HttpMcpClient startServer() throws Exception {
		jadx = new JadxService(IndexConfig.disabled());
		jadx.load(FixtureApk.apk());
		server = new HttpMcpServer(jadx, "127.0.0.1", 0);
		server.start();
		int port = server.port();
		client = new HttpMcpClient("http://127.0.0.1:" + port + "/mcp");
		return client;
	}

	@Test
	@Timeout(120)
	void initializeToolsAndCallWorkOverHttp() throws Exception {
		HttpMcpClient client = startServer();

		JsonNode init = client.request(1, "initialize", null);
		assertEquals("jadx-mcp", init.get("result").get("serverInfo").get("name").asString());
		assertTrue(init.get("result").has("capabilities"));

		client.notify("notifications/initialized", null);

		JsonNode tools = client.request(2, "tools/list", null).get("result").get("tools");
		List<String> names = new ArrayList<>();
		tools.forEach(t -> names.add(t.get("name").asString()));
		assertEquals(14, names.size());

		JsonNode call = client.request(3, "tools/call",
				"{\"name\":\"get_apk_info\",\"arguments\":{}}").get("result");
		assertFalse(call.get("isError").asBoolean());
		assertEquals("dev.jadxmcp.fixture",
				call.get("structuredContent").get("manifestPackage").asString());

		JsonNode list = client.request(4, "tools/call",
				"{\"name\":\"list_classes\",\"arguments\":{\"limit\":3}}").get("result");
		assertTrue(list.get("structuredContent").get("items").size() >= 1);
	}

	@Test
	@Timeout(120)
	void toolErrorOverHttpIsStructured() throws Exception {
		HttpMcpClient client = startServer();
		client.request(1, "initialize", null);
		client.notify("notifications/initialized", null);

		JsonNode err = client.request(2, "tools/call",
				"{\"name\":\"get_class_outline\",\"arguments\":{\"class\":\"Lno/such/Thing;\"}}")
				.get("result");
		assertTrue(err.get("isError").asBoolean());
		assertEquals("CLASS_NOT_FOUND", err.get("structuredContent").get("code").asString());
	}
}
