package dev.jadxmcp.core;

import java.util.ArrayList;
import java.util.List;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import dev.jadxmcp.model.SymbolRef;
import dev.jadxmcp.model.XrefInfo;

/**
 * Phase 1 {@link XrefService}: reads jadx usage info which is computed at load
 * time from raw dex instructions (no decompilation required).
 */
public final class JadxXrefService implements XrefService {

	private final SymbolResolver resolver;

	public JadxXrefService(SymbolResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public XrefInfo incoming(String symbolType, String symbolId, int limit) {
		List<SymbolRef> all = new ArrayList<>();
		SymbolRef symbol;
		switch (symbolType) {
			case "class":
				ClassNode cls = resolver.resolveClass(symbolId);
				if (cls == null) {
					return null;
				}
				symbol = ref(cls);
				// classes referencing this class
				for (ClassNode useCls : cls.getUseIn()) {
					all.add(ref(useCls));
				}
				// methods referencing this class (finer signal)
				for (MethodNode useMth : cls.getUseInMth()) {
					all.add(ref(useMth));
				}
				break;
			case "method":
				MethodNode mth = resolver.resolveMethod(symbolId);
				if (mth == null) {
					return null;
				}
				symbol = ref(mth);
				for (MethodNode caller : mth.getUseIn()) {
					all.add(ref(caller));
				}
				break;
			case "field":
				FieldNode fld = resolver.resolveField(symbolId);
				if (fld == null) {
					return null;
				}
				symbol = ref(fld);
				for (MethodNode user : fld.getUseIn()) {
					all.add(ref(user));
				}
				break;
			default:
				throw new IllegalArgumentException(
						"invalid symbolType '" + symbolType + "' (expected class|method|field)");
		}
		int total = all.size();
		boolean truncated = total > limit;
		List<SymbolRef> page = truncated ? new ArrayList<>(all.subList(0, limit)) : all;
		return new XrefInfo(symbol, page, total, truncated ? true : null);
	}

	private SymbolRef ref(ClassNode cls) {
		return new SymbolRef("class", SymbolResolver.classId(cls),
				cls.getClassInfo().getAliasShortName(), parentName(cls));
	}

	private SymbolRef ref(MethodNode mth) {
		return new SymbolRef("method", SymbolResolver.methodId(mth),
				mth.getMethodInfo().getAlias(), aliasName(mth.getParentClass()));
	}

	private SymbolRef ref(FieldNode fld) {
		return new SymbolRef("field", SymbolResolver.fieldId(fld),
				fld.getFieldInfo().getAlias(), aliasName(fld.getParentClass()));
	}

	private String parentName(ClassNode cls) {
		ClassNode topCls = cls.getTopParentClass();
		return topCls == cls ? null : aliasName(topCls);
	}

	private String aliasName(ClassNode cls) {
		return cls.getClassInfo().getAliasFullName();
	}
}
