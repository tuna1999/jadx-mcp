package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Metadata about the currently loaded input. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApkInfo(
		boolean loaded,
		String path,
		String fileName,
		Long sizeBytes,
		Integer classCount,
		Integer classCountWithInners,
		Integer packageCount,
		Integer resourceCount,
		Boolean manifestPresent,
		String manifestPackage,
		String jadxVersion,
		String loadedAt,
		IndexState index) {
}
