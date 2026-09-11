package dev.jadxmcp.tools;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.core.xmlgen.ResContainer;
import dev.jadxmcp.core.ApkSession;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.model.ErrorCode;
import dev.jadxmcp.model.Page;
import dev.jadxmcp.model.ResourceContent;
import dev.jadxmcp.model.ResourceEntryInfo;

/** list_resources, get_resource. */
public final class ResourceTools {

	private static final long DEFAULT_MAX_BYTES = 200_000;
	private static final long MAX_MAX_BYTES = 2_000_000;

	private final JadxService jadx;

	public ResourceTools(JadxService jadx) {
		this.jadx = jadx;
	}

	public List<ToolDefinition> tools() {
		return List.of(
				listResources(),
				getResource());
	}

	private ToolDefinition listResources() {
		return new ToolDefinition(
				"list_resources",
				"List resources contained in the loaded APK with pagination. Filter by name substring and type "
						+ "(XML, ARSC, MANIFEST, IMG, MEDIA, LIB, UNKNOWN_BIN, UNKNOWN).",
				"""
						{
						  "type": "object",
						  "properties": {
						    "query": { "type": "string", "description": "Optional substring filter on resource path" },
						    "type": { "type": "string", "description": "Optional resource type filter (see description)" },
						    "offset": { "type": "integer", "minimum": 0, "default": 0 },
						    "limit": { "type": "integer", "minimum": 1, "maximum": 1000, "default": 100 }
						  }
						}
						""",
				args -> {
					Args a = Args.of(args);
					ApkSession session = jadx.requireSession();
					String query = a.optStr("query");
					String type = a.optStr("type");
					int offset = a.intOf("offset", 0, 0, Integer.MAX_VALUE);
					int limit = a.intOf("limit", 100, 1, 1000);

					ResourceType typeFilter = null;
					if (type != null) {
						try {
							typeFilter = ResourceType.valueOf(type.toUpperCase(Locale.ROOT));
						} catch (IllegalArgumentException e) {
							throw new ToolException(ErrorCode.INVALID_ARGUMENT,
									"unknown resource type '" + type + "'");
						}
					}
					final ResourceType typeF = typeFilter;
					List<ResourceEntryInfo> all = session.resources().stream()
							.filter(r -> query == null || r.getOriginalName().toLowerCase(Locale.ROOT)
									.contains(query.toLowerCase(Locale.ROOT)))
							.filter(r -> typeF == null || r.getType() == typeF)
							.map(r -> new ResourceEntryInfo(r.getOriginalName(), r.getDeobfName(),
									r.getType().name()))
							.toList();
					int from = Math.min(offset, all.size());
					int to = Math.min(offset + limit, all.size());
					return Map.of("items", all.subList(from, to), "page", Page.of(offset, limit, all.size()));
				});
	}

	private ToolDefinition getResource() {
		return new ToolDefinition(
				"get_resource",
				"Content of one resource by its stable path inside the APK. Text resources (xml/arsc/manifest)" +
						"are returned decoded; binary resources as base64, truncated at maxBytes.",
				"""
						{
						  "type": "object",
						  "properties": {
						    "path": { "type": "string", "description": "Resource path as returned by list_resources, e.g. 'res/values/strings.xml'" },
						    "maxBytes": { "type": "integer", "minimum": 1000, "maximum": 2000000, "default": 200000 }
						  },
						  "required": ["path"]
						}
						""",
				args -> {
					Args a = Args.of(args);
					String path = a.str("path");
					long maxBytes = a.intOf("maxBytes", (int) DEFAULT_MAX_BYTES, 1000, (int) MAX_MAX_BYTES);
					ApkSession session = jadx.requireSession();
					ResourceFile res = session.resources().stream()
							.filter(r -> r.getOriginalName().equals(path) || r.getDeobfName().equals(path))
							.findFirst()
							.orElseThrow(() -> new ToolException(ErrorCode.RESOURCE_NOT_FOUND,
									"resource not found: " + path));
					ResContainer container;
					try {
						container = res.loadContent();
					} catch (Exception e) {
						throw new ToolException(ErrorCode.INTERNAL_ERROR,
								"failed to load resource " + path, String.valueOf(e.getMessage()));
					}
					if (container == null) {
						throw new ToolException(ErrorCode.RESOURCE_NOT_FOUND, "resource has no content: " + path);
					}
					return contentOf(session, res, container, maxBytes);
				});
	}

	private ResourceContent contentOf(ApkSession session, ResourceFile res, ResContainer container, long maxBytes) {
		switch (container.getDataType()) {
			case TEXT:
				String text = container.getText().getCodeStr();
				byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
				long total = bytes.length;
				boolean truncated = total > maxBytes;
				String returnedText = truncated
						? new String(bytes, 0, (int) maxBytes, StandardCharsets.UTF_8)
						: text;
				return new ResourceContent(res.getOriginalName(), res.getType().name(), "text",
						returnedText, null, total, returnedText.length(), truncated, (int) maxBytes);
			case RES_TABLE:
				// decoded resource table (resources.arsc): expose root text content
				String tableText = container.getText() == null ? null : container.getText().getCodeStr();
				int tableLen = tableText == null ? 0 : tableText.length();
				return new ResourceContent(res.getOriginalName(), res.getType().name(), "text",
						tableText, null, tableLen, tableLen, false, (int) maxBytes);
			case DECODED_DATA:
				byte[] data = container.getDecodedData();
				return binaryContent(res, data == null ? new byte[0] : data, maxBytes);
			case RES_LINK:
			default:
				// undecoded entry: read raw zip bytes and expose as text when plausible
				byte[] raw = readRawEntry(session, res);
				String asText = raw.length > 0 && raw.length <= 1_000_000 ? tryDecodeAsText(raw) : null;
				if (asText != null) {
					long totalChars = asText.length();
					boolean txtTruncated = totalChars > maxBytes;
					String txt = txtTruncated ? asText.substring(0, (int) maxBytes) : asText;
					return new ResourceContent(res.getOriginalName(), res.getType().name(), "text",
							txt, null, totalChars, txt.length(), txtTruncated, (int) maxBytes);
				}
				return binaryContent(res, raw, maxBytes);
		}
	}

	/** Strict UTF-8 decode heuristic: returns null for binary-looking data. */
	private static String tryDecodeAsText(byte[] data) {
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data);
		try {
			String s = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
					.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
					.decode(buf).toString();
			if (s.indexOf('\u0000') >= 0) {
				return null;
			}
			int printable = 0;
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0x7F)) {
					printable++;
				}
			}
			return s.length() == 0 || printable * 100L / s.length() >= 90 ? s : null;
		} catch (java.nio.charset.CharacterCodingException e) {
			return null;
		}
	}

	private byte[] readRawEntry(ApkSession session, ResourceFile res) {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(session.inputPath().toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry(res.getOriginalName());
			if (entry == null) {
				return new byte[0];
			}
			try (var in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		} catch (Exception e) {
			return new byte[0];
		}
	}

	private ResourceContent binaryContent(ResourceFile res, byte[] data, long maxBytes) {
		long totalBytes = data.length;
		boolean binTruncated = totalBytes > maxBytes;
		byte[] returned = binTruncated
				? java.util.Arrays.copyOf(data, (int) maxBytes)
				: data;
		return new ResourceContent(res.getOriginalName(), res.getType().name(), "binary",
				null, Base64.getEncoder().encodeToString(returned),
				totalBytes, returned.length, binTruncated, (int) maxBytes);
	}
}
