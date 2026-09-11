package dev.jadxmcp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.ConstClassNode;
import jadx.core.dex.instructions.ConstStringNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.instructions.IndexInsnNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import dev.jadxmcp.core.IndexStore.EdgeRow;
import dev.jadxmcp.core.IndexStore.StringRow;

/**
 * Builds the {@link IndexStore} content from raw dex instructions — no
 * decompilation. For every method with code: decode instructions
 * ({@code load()}), collect string constants ({@code CONST_STR}) and outgoing
 * reference edges (invocations, field accesses, instantiations, class
 * literals), then free the instruction array ({@code unload()}). Incoming
 * usage info computed by jadx at load time is stored separately and survives
 * this walk.
 */
public final class IndexBuilder {

	private static final Logger LOG = LoggerFactory.getLogger(IndexBuilder.class);

	private IndexBuilder() {
	}

	/** Walk every class (incl. inners) and populate the store. Never throws. */
	public static void build(RootNode root, IndexStore store) {
		for (ClassNode cls : root.getClasses(true)) {
			try {
				buildClass(cls, store);
			} catch (Throwable t) {
				LOG.debug("index: failed to index class {}", cls.getRawName(), t);
			}
		}
	}

	private static void buildClass(ClassNode cls, IndexStore store) {
		String classId = SymbolResolver.classId(cls);
		String className = cls.getClassInfo().getAliasFullName();
		List<StringRow> strings = new ArrayList<>();
		Map<String, List<EdgeRow>> edges = new HashMap<>();
		for (MethodNode mth : cls.getMethods()) {
			if (mth.isNoCode()) {
				continue;
			}
			String srcId = SymbolResolver.methodId(mth);
			Set<String> seen = new HashSet<>();
			try {
				mth.load();
				for (InsnNode insn : mth.getInstructions()) {
					if (insn == null) {
						continue; // jadx preallocates the array; trailing slots stay null
					}
					collectInsn(cls, className, mth, srcId, insn, strings, seen, edges);
				}
			} catch (Exception e) {
				LOG.debug("index: failed to walk {}", srcId, e);
			} finally {
				mth.unload();
			}
		}
		if (!strings.isEmpty()) {
			store.insertStrings(strings);
		}
		edges.forEach(store::insertEdges);
	}

	private static void collectInsn(ClassNode cls, String className, MethodNode mth, String srcId, InsnNode insn,
			List<StringRow> strings, Set<String> seen, Map<String, List<EdgeRow>> edges) {
		InsnType type = insn.getType();
		switch (type) {
		case CONST_STR -> {
			if (insn instanceof ConstStringNode cstr && !cstr.getString().isEmpty()) {
				strings.add(new StringRow(SymbolResolver.classId(cls), className, srcId,
						mth.getMethodInfo().getAlias(), cstr.getString()));
			}
		}
			case INVOKE -> {
				if (insn instanceof InvokeNode inv) {
					addEdge(seen, edges, srcId, methodRef(inv.getCallMth()));
				}
			}
			case IGET, IPUT, SGET, SPUT -> {
				if (insn instanceof IndexInsnNode idx && idx.getIndex() instanceof FieldInfo fi) {
					addEdge(seen, edges, srcId, fieldRef(fi));
				}
			}
		case NEW_INSTANCE, CONST_CLASS -> {
			if (insn instanceof ConstClassNode cc && cc.getClsType() != null && cc.getClsType().isTypeKnown()) {
				addEdge(seen, edges, srcId, classRef(cc.getClsType()));
			} else if (insn instanceof IndexInsnNode idx
					&& idx.getIndex() instanceof ArgType t
					&& t.isObject()
					&& t.isTypeKnown()) {
				addEdge(seen, edges, srcId, classRef(t));
			}
		}
			default -> {
				// not a reference-bearing instruction
			}
		}
	}

	private static void addEdge(Set<String> seen, Map<String, List<EdgeRow>> edges, String srcId, EdgeRow edge) {
		if (seen.add(edge.dstId())) {
			edges.computeIfAbsent(srcId, k -> new ArrayList<>()).add(edge);
		}
	}

	/** {@code Lcls;->name(sig)ret} from a raw {@link MethodInfo}. */
	private static EdgeRow methodRef(MethodInfo mi) {
		String id = SymbolResolver.toDexType(mi.getDeclClass().makeRawFullName()) + "->" + mi.getShortId();
		return new EdgeRow(id, "method");
	}

	/** {@code Lcls;->name:Ltype;} from a raw {@link FieldInfo}. */
	private static EdgeRow fieldRef(FieldInfo fi) {
		String id = SymbolResolver.toDexType(fi.getDeclClass().makeRawFullName()) + "->" + fi.getShortId();
		return new EdgeRow(id, "field");
	}

	/** {@code Lcls;} from an object {@link ArgType}. */
	private static EdgeRow classRef(ArgType t) {
		String descriptor = t.toString();
		if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
			return new EdgeRow(descriptor, "class");
		}
		// ArgType.toString may use dotted or slashed form; normalize both
		return new EdgeRow(SymbolResolver.toDexType(descriptor.replace('/', '.')), "class");
	}
}
