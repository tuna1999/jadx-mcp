package dev.jadxmcp.tools;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.jadxmcp.core.ApkSession;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.model.ErrorCode;
import dev.jadxmcp.model.XrefInfo;

/** get_xrefs: incoming usages of a class, method or field. */
public final class XrefTools {

	private final JadxService jadx;

	public XrefTools(JadxService jadx) {
		this.jadx = jadx;
	}

	public List<ToolDefinition> tools() {
		return List.of(getXrefs());
	}

	private ToolDefinition getXrefs() {
		return new ToolDefinition(
				"get_xrefs",
				"Incoming usages (xrefs) of a symbol. symbolType is 'class', 'method' or 'field'; "
						+ "id is the stable DEX-style id. Outgoing xrefs may be added in a later phase.",
				"""
						{
						  "type": "object",
						  "properties": {
						    "symbolType": { "type": "string", "enum": ["class", "method", "field"] },
						    "id": { "type": "string", "description": "Stable symbol id, e.g. 'Lcom/example/Foo;->decrypt([B[B)[B'" },
						    "limit": { "type": "integer", "minimum": 1, "maximum": 1000, "default": 200 }
						  },
						  "required": ["symbolType", "id"]
						}
						""",
				args -> {
					Args a = Args.of(args);
					String symbolType = a.str("symbolType").toLowerCase(Locale.ROOT);
					String id = a.str("id");
					int limit = a.intOf("limit", 200, 1, 1000);
					ApkSession session = jadx.requireSession();
					XrefInfo info;
					try {
						info = session.xref().incoming(symbolType, id, limit);
					} catch (IllegalArgumentException e) {
						throw new ToolException(ErrorCode.INVALID_ARGUMENT, e.getMessage());
					}
					if (info == null) {
						switch (symbolType) {
							case "method" -> throw new ToolException(ErrorCode.METHOD_NOT_FOUND,
									"method not found: " + id);
							case "field" -> throw new ToolException(ErrorCode.FIELD_NOT_FOUND,
									"field not found: " + id);
							default -> throw new ToolException(ErrorCode.CLASS_NOT_FOUND,
									"class not found: " + id);
						}
					}
					return info;
				});
	}
}
