package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Phase 2 index status reported by get_apk_info / load_apk. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexState(
		String state,
		Long stringCount,
		Long edgeCount) {

	public static final String BUILDING = "building";
	public static final String READY = "ready";
	public static final String DISABLED = "disabled";
}
