package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Content of one resource. Text resources (xml, arsc, manifest) are returned as
 * text; binary resources as base64 with explicit truncation metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceContent(
		String name,
		String type,
		String kind,
		String text,
		String base64,
		long totalBytes,
		long returnedBytes,
		boolean truncated,
		Integer maxBytes) {
}
