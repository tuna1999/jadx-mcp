package dev.jadxmcp.core;

import dev.jadxmcp.model.XrefInfo;

/**
 * Cross-reference service. Incoming usages come from jadx usage info (built at
 * load time from raw dex instructions). The Phase 2 index adds persistent
 * outgoing edges; the interface returns both directions in one call.
 */
public interface XrefService {

	/**
	 * Both xref directions of a symbol.
	 *
	 * @param symbolType one of {@code class|method|field}
	 * @param symbolId   stable DEX-style id
	 * @param limit      max entries per direction
	 * @return null when the symbol is unknown; throws IllegalArgumentException
	 *         on an invalid symbolType
	 */
	XrefInfo xrefs(String symbolType, String symbolId, int limit);
}
