package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Decoded AndroidManifest.xml. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManifestInfo(
		boolean present,
		String packageName,
		String versionName,
		Integer versionCode,
		String xml) {
}
