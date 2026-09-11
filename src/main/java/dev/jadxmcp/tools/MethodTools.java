package dev.jadxmcp.tools;

import java.util.List;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.ClassNode;
import dev.jadxmcp.core.SymbolResolver;
import jadx.core.dex.nodes.MethodNode;
import dev.jadxmcp.core.ApkSession;
import dev.jadxmcp.core.CodeCache;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.model.ErrorCode;
import dev.jadxmcp.model.MethodSource;

/** get_method_source: the primary code-reading API for agents. */
public final class MethodTools {

	private final JadxService jadx;

	public MethodTools(JadxService jadx) {
		this.jadx = jadx;
	}

	public List<ToolDefinition> tools() {
		return List.of(getMethodSource());
	}

	private ToolDefinition getMethodSource() {
		return new ToolDefinition(
				"get_method_source",
				"Decompiled source of a single method, addressed by its stable id "
						+ "'Lcom/example/Foo;->decrypt([B[B)[B' (from get_class_outline or search_methods).",
				"""
						{
						  "type": "object",
						  "properties": {
						    "method": { "type": "string", "description": "Method id like 'Lcom/example/Foo;->decrypt([B[B)[B'" },
						    "maxChars": { "type": "integer", "minimum": 1000, "maximum": 500000, "default": 50000 }
						  },
						  "required": ["method"]
						}
						""",
				args -> {
					Args a = Args.of(args);
					String methodId = a.str("method");
					int maxChars = a.intOf("maxChars", ClassTools.DEFAULT_MAX_CHARS, 1000, ClassTools.MAX_MAX_CHARS);
					ApkSession session = jadx.requireSession();
					MethodNode mth;
					try {
						mth = session.resolver().resolveMethod(methodId);
					} catch (IllegalArgumentException e) {
						throw new ToolException(ErrorCode.INVALID_SYMBOL_ID, e.getMessage());
					}
					if (mth == null) {
						throw new ToolException(ErrorCode.METHOD_NOT_FOUND, "method not found: " + methodId);
					}

					String source = null;
					if (!mth.isNoCode() && !mth.contains(AFlag.DONT_GENERATE)) {
						try {
							ClassNode topCls = mth.getParentClass().getTopParentClass();
							CodeCache cache = session.codeCache();
							cache.getClassSource(topCls); // ensure decompiled + cached
							source = mth.getCodeStr();
						} catch (RuntimeException e) {
							throw new ToolException(ErrorCode.DECOMPILATION_FAILED,
									"decompilation failed for " + methodId, String.valueOf(e.getMessage()));
						}
					}
					boolean noCode = source == null;
					int total = source == null ? 0 : source.length();
					boolean truncated = total > maxChars;
					String returned = noCode ? null : (truncated ? source.substring(0, maxChars) : source);
					return new MethodSource(
							SymbolResolver.methodId(mth),
							mth.getMethodInfo().getAlias(),
							mth.getParentClass().getClassInfo().getAliasFullName(),
							mth.getMethodInfo().getShortId(),
							ClassTools.typeName(mth.getReturnType()),
							mth.getArgTypes().stream().map(ClassTools::typeName).toList(),
							mth.getAccessFlags().makeString(true),
							returned,
							noCode,
							truncated,
							total,
							returned == null ? 0 : returned.length(),
							maxChars);
				});
	}
}
