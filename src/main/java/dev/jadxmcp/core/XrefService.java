package dev.jadxmcp.core;

import dev.jadxmcp.model.XrefInfo;

/**
 * Cross-reference service. Phase 1 computes incoming usages from jadx usage
 * info (built at load time from raw dex instructions). Phase 2 can extend this
 * with a persistent xref index and outgoing usages.
 */
public interface XrefService {

	/** Incoming usages of a class/method/field symbol id. */
	XrefInfo incoming(String symbolType, String symbolId, int limit);
}
