package dev.jadxmcp.model;

/** Machine-readable error codes returned as structured MCP tool errors. */
public enum ErrorCode {
	NO_APK_LOADED,
	FILE_NOT_FOUND,
	INVALID_INPUT_FILE,
	CLASS_NOT_FOUND,
	METHOD_NOT_FOUND,
	FIELD_NOT_FOUND,
	RESOURCE_NOT_FOUND,
	INVALID_SYMBOL_ID,
	INVALID_ARGUMENT,
	DECOMPILATION_FAILED,
	MANIFEST_NOT_FOUND,
	INTERNAL_ERROR
}
