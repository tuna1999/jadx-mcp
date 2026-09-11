package dev.jadxmcp.core;

import java.util.ArrayList;
import java.util.List;

import dev.jadxmcp.core.IndexStore.EdgeRow;
import dev.jadxmcp.model.SymbolRef;
import dev.jadxmcp.model.XrefInfo;

/**
 * {@link XrefService} with outgoing edges from the Phase 2 index. Incoming
 * edges and symbol resolution come from the fallback (jadx usage info). While
 * the index is building, outgoing fields stay absent — callers see the Phase 1
 * shape.
 */
public final class IndexedXrefService implements XrefService {

	private final XrefService fallback;
	private final IndexStore store;

	public IndexedXrefService(XrefService fallback, IndexStore store) {
		this.fallback = fallback;
		this.store = store;
	}

	@Override
	public XrefInfo xrefs(String symbolType, String symbolId, int limit) {
		XrefInfo base = fallback.xrefs(symbolType, symbolId, limit);
		if (base == null || !store.ready()) {
			return base;
		}
		switch (symbolType) {
			case "method" -> {
				return withOutgoing(base, store.outgoingOfMethod(symbolId, limit), limit);
			}
			case "class" -> {
				return withOutgoing(base, store.outgoingOfClass(symbolId, limit), limit);
			}
			default -> {
				return base; // fields reference nothing
			}
		}
	}

	private XrefInfo withOutgoing(XrefInfo base, List<EdgeRow> rows, int limit) {
		boolean truncated = rows.size() > limit;
		List<EdgeRow> page = truncated ? rows.subList(0, limit) : rows;
		List<SymbolRef> out = new ArrayList<>(page.size());
		for (EdgeRow row : page) {
			out.add(toRef(row));
		}
		// truncated means "at least limit+1 distinct destinations"
		return new XrefInfo(base.symbol(), base.incoming(), base.incomingCount(), base.incomingTruncated(),
				out, truncated ? limit + 1 : out.size(), truncated ? true : null);
	}

	/** Build a display {@link SymbolRef} from a raw destination id. */
	private static SymbolRef toRef(EdgeRow row) {
		String id = row.dstId();
		return switch (row.dstType()) {
			case "method" -> {
				int arrow = id.indexOf("->");
				int paren = id.indexOf('(', arrow);
				String name = paren > arrow ? id.substring(arrow + 2, paren) : id.substring(arrow + 2);
				yield new SymbolRef("method", id, name, classNameOf(id, arrow));
			}
			case "field" -> {
				int arrow = id.indexOf("->");
				int colon = id.indexOf(':', arrow);
				String name = colon > arrow ? id.substring(arrow + 2, colon) : id.substring(arrow + 2);
				yield new SymbolRef("field", id, name, classNameOf(id, arrow));
			}
			default -> {
				String name = id;
				int slash = id.lastIndexOf('/');
				if (slash >= 0) {
					name = id.substring(slash + 1);
					name = name.endsWith(";") ? name.substring(0, name.length() - 1) : name;
				}
				yield new SymbolRef("class", id, name, null);
			}
		};
	}

	private static String classNameOf(String id, int arrow) {
		if (arrow <= 0 || id.charAt(0) != 'L') {
			return null;
		}
		return id.substring(1, arrow - 1).replace('/', '.');
	}
}
