package dev.jadxmcp.core;

import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

/**
 * Maps MCP symbol ids to jadx nodes and back.
 *
 * <p>Stable DEX-style identities:
 * <ul>
 *   <li>class: {@code Lcom/example/Foo;}</li>
 *   <li>method: {@code Lcom/example/Foo;->decrypt([B[B)[B}</li>
 *   <li>field: {@code Lcom/example/Foo;->key:[B}</li>
 * </ul>
 *
 * Java-style dotted names ({@code com.example.Foo}) are accepted as input
 * convenience for classes.
 */
public final class SymbolResolver {

	private final RootNode root;

	public SymbolResolver(RootNode root) {
		this.root = root;
	}

	// ------------------------------------------------------------------ ids

	public static String classId(ClassNode cls) {
		return toDexType(cls.getClassInfo().makeRawFullName());
	}

	public static String methodId(MethodNode mth) {
		return classId(mth.getParentClass()) + "->" + mth.getMethodInfo().getShortId();
	}

	public static String fieldId(FieldNode fld) {
		return classId(fld.getParentClass()) + "->" + fld.getFieldInfo().getShortId();
	}

	/** {@code com.example.Foo} -> {@code Lcom/example/Foo;} (inner classes keep {@code $}). */
	public static String toDexType(String rawFullName) {
		return 'L' + rawFullName.replace('.', '/') + ';';
	}

	// -------------------------------------------------------------- resolve

	/** Resolve a class by dex id or dotted name. Returns null when unknown. */
	public ClassNode resolveClass(String idOrName) {
		if (idOrName == null || idOrName.isBlank()) {
			return null;
		}
		String name = idOrName.trim();
		if (name.startsWith("L") && name.endsWith(";") && name.length() > 2) {
			name = name.substring(1, name.length() - 1);
		}
		name = name.replace('/', '.');
		if (name.isEmpty()) {
			return null;
		}
		ClassNode cls = root.resolveRawClass(name);
		if (cls == null) {
			cls = root.searchClassByFullAlias(name);
		}
		if (cls == null) {
			cls = root.resolveClass(name);
		}
		return cls;
	}

	/**
	 * Resolve a method by id {@code Lcls;->name(sig)ret}.
	 *
	 * @return null when class or method unknown; id malformed ids throw
	 */
	public MethodNode resolveMethod(String id) {
		if (id == null) {
			return null;
		}
		int sep = id.indexOf("->");
		if (sep <= 0 || sep + 2 >= id.length()) {
			throw new IllegalArgumentException("malformed method id: '" + id
					+ "' (expected 'Lcom/example/Foo;->name(Args)Ret')");
		}
		String classPart = id.substring(0, sep);
		String shortId = id.substring(sep + 2);
		if (!shortId.contains("(")) {
			throw new IllegalArgumentException("malformed method id (missing signature): '" + id + "'");
		}
		ClassNode cls = resolveClass(classPart);
		if (cls == null) {
			return null;
		}
		return cls.searchMethodByShortId(shortId);
	}

	/** Resolve a field by id {@code Lcls;->name:Ltype;}. */
	public FieldNode resolveField(String id) {
		if (id == null) {
			return null;
		}
		int sep = id.indexOf("->");
		if (sep <= 0 || sep + 2 >= id.length()) {
			throw new IllegalArgumentException("malformed field id: '" + id
					+ "' (expected 'Lcom/example/Foo;->name:Ltype;')");
		}
		String classPart = id.substring(0, sep);
		String fieldPart = id.substring(sep + 2);
		ClassNode cls = resolveClass(classPart);
		if (cls == null) {
			return null;
		}
		FieldNode exact = null;
		FieldNode byName = null;
		int byNameCount = 0;
		for (FieldNode fld : cls.getFields()) {
			if (fld.getFieldInfo().getShortId().equals(fieldPart)) {
				exact = fld;
				break;
			}
			if (fld.getFieldInfo().getName().equals(fieldPart)) {
				byName = fld;
				byNameCount++;
			}
		}
		if (exact != null) {
			return exact;
		}
		// name-only reference is unambiguous only for a single field with that name
		return byNameCount == 1 ? byName : null;
	}

	public MethodInfo methodInfoOf(MethodNode mth) {
		return mth.getMethodInfo();
	}
}
