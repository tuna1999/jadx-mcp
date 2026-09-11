package dev.jadxmcp.tools;

import java.util.Map;

import dev.jadxmcp.model.ErrorCode;

/** Typed access to tool call arguments. */
public final class Args {

	private final Map<String, Object> args;

	private Args(Map<String, Object> args) {
		this.args = args == null ? Map.of() : args;
	}

	public static Args of(Map<String, Object> args) {
		return new Args(args);
	}

	public String str(String name) {
		Object v = args.get(name);
		if (v == null) {
			throw new ToolException(ErrorCode.INVALID_ARGUMENT, "missing required argument '" + name + "'");
		}
		if (!(v instanceof String s) || s.isBlank()) {
			throw new ToolException(ErrorCode.INVALID_ARGUMENT, "argument '" + name + "' must be a non-empty string");
		}
		return s;
	}

	public String optStr(String name) {
		Object v = args.get(name);
		if (v == null) {
			return null;
		}
		if (!(v instanceof String s)) {
			throw new ToolException(ErrorCode.INVALID_ARGUMENT, "argument '" + name + "' must be a string");
		}
		return s.isBlank() ? null : s;
	}

	public int intOf(String name, int def, int min, int max) {
		Object v = args.get(name);
		if (v == null) {
			return def;
		}
		int value;
		if (v instanceof Number n) {
			value = n.intValue();
		} else if (v instanceof String s) {
			try {
				value = Integer.parseInt(s);
			} catch (NumberFormatException e) {
				throw new ToolException(ErrorCode.INVALID_ARGUMENT, "argument '" + name + "' must be an integer");
			}
		} else {
			throw new ToolException(ErrorCode.INVALID_ARGUMENT, "argument '" + name + "' must be an integer");
		}
		return Math.max(min, Math.min(max, value));
	}

	public boolean boolOf(String name, boolean def) {
		Object v = args.get(name);
		if (v == null) {
			return def;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		if (v instanceof String s) {
			return Boolean.parseBoolean(s);
		}
		throw new ToolException(ErrorCode.INVALID_ARGUMENT, "argument '" + name + "' must be a boolean");
	}

	public boolean has(String name) {
		return args.containsKey(name);
	}
}
