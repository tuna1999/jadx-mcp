package dev.jadxmcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Structured error payload. Never contains stack traces; details are logged
 * server-side only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorPayload(ErrorCode code, String message, String detail) {

	public ErrorPayload(ErrorCode code, String message) {
		this(code, message, null);
	}
}
