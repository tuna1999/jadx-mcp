package dev.jadxmcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.jadxmcp.fixture.FixtureApk;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spawns the packaged fat jar in stdio mode and speaks raw MCP JSON-RPC over
 * the process pipes. Verifies the handshake, a tool call, and that stdout
 * carries nothing but protocol JSON (logs must stay on stderr).
 */
class StdioMcpServerIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static String jarPath;
	private Process process;
	private BufferedWriter toServer;
	private BufferedReader stdout;
	private BufferedReader stderr;

	@BeforeAll
	static void requireJar() {
		jarPath = System.getProperty("jadxmcp.jar");
		Assumptions.assumeTrue(jarPath != null && Path.of(jarPath).toFile().exists(),
				"fat jar not built (jadxmcp.jar property missing)");
		FixtureApk.apk();
	}

	private void start(String... extraArgs) throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add(ProcessHandle.current().info().command().orElse("java"));
		cmd.add("-jar");
		cmd.add(jarPath);
		cmd.add("stdio");
		for (String a : extraArgs) {
			cmd.add(a);
		}
		process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
		toServer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
		stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
	}

	@AfterEach
	void stopProcess() {
		if (process != null && process.isAlive()) {
			process.destroyForcibly();
		}
	}

	private void send(String json) throws Exception {
		toServer.write(json);
		toServer.write("\n");
		toServer.flush();
	}

	private JsonNode readResponse(long id) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
		while (System.nanoTime() < deadline) {
			String line = stdout.readLine();
			assertNotNull(line, "stdout closed before response for id " + id);
			JsonNode msg = JSON.readTree(line); // throws if stdout is polluted
			if (msg.has("id") && msg.get("id").asLong() == id) {
				return msg;
			}
		}
		throw new IllegalStateException("timeout waiting for response id " + id);
	}

	private String drainStderr() throws Exception {
		StringBuilder sb = new StringBuilder();
		while (stderr.ready()) {
			sb.append((char) stderr.read());
		}
		return sb.toString();
	}

	@Test
	@Timeout(180)
	void handshakeToolsAndToolCallWorkOverStdio() throws Exception {
		start("--input", FixtureApk.apk().toString(), "--index-dir", Path.of("build", "fixtures", "it-index").toString());
		send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
				+ "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"it\",\"version\":\"1.0\"}}}");
		JsonNode init = readResponse(1);
		assertEquals("jadx-mcp", init.get("result").get("serverInfo").get("name").asString());

		send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

		send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
		JsonNode tools = readResponse(2).get("result").get("tools");
		List<String> names = new ArrayList<>();
		tools.forEach(t -> names.add(t.get("name").asString()));
		assertTrue(names.contains("load_apk"));
		assertTrue(names.contains("get_method_source"));
		assertEquals(14, names.size(), "Phase 1 exposes exactly 14 tools: " + names);

		send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"get_apk_info\",\"arguments\":{}}}");
		JsonNode call = readResponse(3).get("result");
		assertEquals(false, call.get("isError").asBoolean());
		assertEquals("dev.jadxmcp.fixture",
				call.get("structuredContent").get("manifestPackage").asString());

		// stdout stayed pure JSON the whole time (readResponse would have thrown),
		// and logs landed on stderr
		String err = drainStderr();
		assertTrue(err.contains("stdio transport ready"), "expected startup log on stderr: " + err);
	}

	@Test
	@Timeout(180)
	void loadApkToolWithoutPreloadedInput() throws Exception {
		start(); // no --input
		send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
				+ "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"it\",\"version\":\"1.0\"}}}");
		readResponse(1);
		send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

		// before load: structured NO_APK_LOADED error
		send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"list_classes\",\"arguments\":{}}}");
		JsonNode err = readResponse(2).get("result");
		assertEquals(true, err.get("isError").asBoolean());
		assertEquals("NO_APK_LOADED", err.get("structuredContent").get("code").asString());

		// load via tool
		send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"load_apk\",\"arguments\":{\"path\":\""
				+ FixtureApk.apk().toString().replace("\\", "\\\\") + "\"}}}");
		JsonNode load = readResponse(3).get("result");
		assertEquals(false, load.get("isError").asBoolean());
		assertTrue(load.get("structuredContent").get("classCount").asInt() >= 5);
	}

	@Test
	@Timeout(60)
	void processExitsWhenStdinCloses() throws Exception {
		start("--input", FixtureApk.apk().toString(), "--index-dir", Path.of("build", "fixtures", "it-index").toString());
		send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
				+ "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"it\",\"version\":\"1.0\"}}}");
		readResponse(1);
		toServer.close();
		boolean exited = process.waitFor(30, TimeUnit.SECONDS);
		assertTrue(exited, "process must exit after stdin EOF");
	}
}
