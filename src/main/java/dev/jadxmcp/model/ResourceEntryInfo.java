package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One entry of the APK resource listing. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceEntryInfo(
		String name,
		String deobfName,
		String type) {
}
