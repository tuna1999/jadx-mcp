package dev.jadxmcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Cross-reference (usage) information for a symbol. Outgoing edges are
 * available when the Phase 2 index is ready; fields have no outgoing refs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XrefInfo(
		SymbolRef symbol,
		List<SymbolRef> incoming,
		int incomingCount,
		Boolean incomingTruncated,
		List<SymbolRef> outgoing,
		Integer outgoingCount,
		Boolean outgoingTruncated) {
}
