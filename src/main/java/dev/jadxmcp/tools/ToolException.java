package dev.jadxmcp.tools;

import dev.jadxmcp.model.ErrorCode;

/** Tool-level error carrying a machine-readable code. */
public final class ToolException extends RuntimeException {

	private final ErrorCode code;
	private final String detail;

	public ToolException(ErrorCode code, String message) {
		this(code, message, null);
	}

	public ToolException(ErrorCode code, String message, String detail) {
		super(message);
		this.code = code;
		this.detail = detail;
	}

	public ErrorCode code() {
		return code;
	}

	public String detail() {
		return detail;
	}
}
