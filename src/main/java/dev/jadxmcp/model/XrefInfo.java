package dev.jadxmcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Cross-reference (usage) information for a symbol. Incoming usages only in Phase 1. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XrefInfo(
		SymbolRef symbol,
		List<SymbolRef> incoming,
		int incomingCount,
		Boolean incomingTruncated) {
}
