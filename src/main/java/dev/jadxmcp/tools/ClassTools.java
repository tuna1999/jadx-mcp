package dev.jadxmcp.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import dev.jadxmcp.core.ApkSession;
import dev.jadxmcp.core.CodeCache;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.core.SymbolResolver;
import dev.jadxmcp.model.ClassEntry;
import dev.jadxmcp.model.ClassOutline;
import dev.jadxmcp.model.ClassSource;
import dev.jadxmcp.model.ErrorCode;
import dev.jadxmcp.model.FieldEntry;
import dev.jadxmcp.model.MethodEntry;
import dev.jadxmcp.model.Page;

/** list_classes, get_class_outline, get_class_source. */
public final class ClassTools {

	static final int DEFAULT_LIMIT = 100;
	static final int MAX_LIMIT = 1000;
	static final int DEFAULT_MAX_CHARS = 50_000;
	static final int MAX_MAX_CHARS = 500_000;

	private final JadxService jadx;

	public ClassTools(JadxService jadx) {
		this.jadx = jadx;
	}

	public List<ToolDefinition> tools() {
		return List.of(
				listClasses(),
				getClassOutline(),
				getClassSource());
	}

	private ToolDefinition listClasses() {
		return new ToolDefinition(
				"list_classes",
				"List classes with pagination. Filter by package prefix and/or name substring. "
						+ "Returns stable DEX-style ids usable with get_class_outline/get_class_source.",
				"""
						{
						  "type": "object",
						  "properties": {
						    "package": { "type": "string", "description": "Optional exact or prefix package filter, e.g. 'com.example'" },
						    "query": { "type": "string", "description": "Optional substring filter on class full name" },
						    "offset": { "type": "integer", "minimum": 0, "default": 0 },
						    "limit": { "type": "integer", "minimum": 1, "maximum": 1000, "default": 100 }
						  }
						}
						""",
				args -> {
					Args a = Args.of(args);
					ApkSession session = jadx.requireSession();
					String pkg = a.optStr("package");
					String query = a.optStr("query");
					int offset = a.intOf("offset", 0, 0, Integer.MAX_VALUE);
					int limit = a.intOf("limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

					List<ClassEntry> all = new ArrayList<>();
					for (ClassNode cls : session.root().getClasses(false)) {
						if (cls.contains(AFlag.DONT_GENERATE)) {
							continue;
						}
						String fullName = cls.getClassInfo().getAliasFullName();
						if (pkg != null && !inPackage(cls, pkg)) {
							continue;
						}
						if (query != null
								&& !fullName.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
							continue;
						}
						all.add(new ClassEntry(
								SymbolResolver.classId(cls),
								fullName,
								pkgOf(cls),
								false));
					}
					all.sort(Comparator.comparing(ClassEntry::name));
					int from = Math.min(offset, all.size());
					int to = Math.min(offset + limit, all.size());
					return Map.of(
							"package", pkg == null ? "" : pkg,
							"items", all.subList(from, to),
							"page", Page.of(offset, limit, all.size()));
				});
	}

	private boolean inPackage(ClassNode cls, String pkg) {
		String p = pkgOf(cls);
		return p.equals(pkg) || p.startsWith(pkg + ".");
	}

	private String pkgOf(ClassNode cls) {
		try {
			return cls.getClassInfo().getPackage();
		} catch (RuntimeException e) {
			return "";
		}
	}

	private ToolDefinition getClassOutline() {
		return new ToolDefinition(
				"get_class_outline",
				"Class structure without decompiled source: superclass, interfaces, fields, methods, inner classes. "
						+ "Preferred class-inspection API; use get_class_source only when full source is needed.",
				"""
						{
						  "type": "object",
						  "properties": {
						    "class": { "type": "string", "description": "Class id like 'Lcom/example/Foo;' or dotted name 'com.example.Foo'" }
						  },
						  "required": ["class"]
						}
						""",
				args -> {
					String classId = Args.of(args).str("class");
					ApkSession session = jadx.requireSession();
					ClassNode cls = session.resolver().resolveClass(classId);
					if (cls == null) {
						throw new ToolException(ErrorCode.CLASS_NOT_FOUND, "class not found: " + classId);
					}
					return outline(cls);
				});
	}

	ClassOutline outline(ClassNode cls) {
		List<FieldEntry> fields = cls.getFields().stream()
				.map(f -> new FieldEntry(
						SymbolResolver.fieldId(f),
						f.getFieldInfo().getAlias(),
						typeName(f.getType()),
						f.getAccessFlags().makeString(true)))
				.toList();
		List<MethodEntry> methods = cls.getMethods().stream()
				.map(m -> new MethodEntry(
						SymbolResolver.methodId(m),
						m.getMethodInfo().getAlias(),
						cls.getClassInfo().getAliasFullName(),
						m.getMethodInfo().getShortId(),
						m.getAccessFlags().makeString(true)))
				.toList();
		List<ClassEntry> inner = cls.getInnerClasses().stream()
				.filter(i -> !i.contains(AFlag.DONT_GENERATE))
				.map(i -> new ClassEntry(
						SymbolResolver.classId(i),
						i.getClassInfo().getAliasFullName(),
						pkgOf(i),
						true))
				.toList();
		String outer = cls.getClassInfo().isInner()
				? SymbolResolver.classId(cls.getTopParentClass())
				: null;
		return new ClassOutline(
				SymbolResolver.classId(cls),
				cls.getClassInfo().getAliasFullName(),
				pkgOf(cls),
				cls.getAccessFlags().makeString(true),
				cls.getSuperClass() == null ? null : typeName(cls.getSuperClass()),
				cls.getInterfaces().stream().map(ClassTools::typeName).toList(),
				fields,
				methods,
				inner,
				outer);
	}

	private ToolDefinition getClassSource() {
		return new ToolDefinition(
				"get_class_source",
				"Decompiled Java source of one class. Output is truncated at maxChars; check the truncation metadata.",
				"""
						{
						  "type": "object",
						  "properties": {
						    "class": { "type": "string", "description": "Class id or dotted name" },
						    "maxChars": { "type": "integer", "minimum": 1000, "maximum": 500000, "default": 50000 }
						  },
						  "required": ["class"]
						}
						""",
				args -> {
					Args a = Args.of(args);
					String classId = a.str("class");
					int maxChars = a.intOf("maxChars", DEFAULT_MAX_CHARS, 1000, MAX_MAX_CHARS);
					ApkSession session = jadx.requireSession();
					ClassNode cls = session.resolver().resolveClass(classId);
					if (cls == null) {
						throw new ToolException(ErrorCode.CLASS_NOT_FOUND, "class not found: " + classId);
					}
					String source;
					try {
						CodeCache cache = session.codeCache();
						source = cache.getClassSource(cls.getTopParentClass());
					} catch (RuntimeException e) {
						throw new ToolException(ErrorCode.DECOMPILATION_FAILED,
								"decompilation failed for " + classId, String.valueOf(e.getMessage()));
					}
					if (source == null) {
						throw new ToolException(ErrorCode.DECOMPILATION_FAILED,
								"no decompilable code for " + classId);
					}
					int total = source.length();
					boolean truncated = total > maxChars;
					String returned = truncated ? source.substring(0, maxChars) : source;
					return new ClassSource(
							SymbolResolver.classId(cls),
							cls.getClassInfo().getAliasFullName(),
							returned,
							total,
							returned.length(),
							truncated,
							maxChars);
				});
	}

	static String typeName(jadx.core.dex.instructions.args.ArgType type) {
		return type == null ? null : type.toString();
	}
}
