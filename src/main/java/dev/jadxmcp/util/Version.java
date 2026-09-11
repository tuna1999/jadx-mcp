package dev.jadxmcp.util;

/** Application version read from the jar manifest. */
public final class Version {

	private static final String VALUE = readVersion();

	private Version() {
	}

	public static String value() {
		return VALUE;
	}

	private static String readVersion() {
		try {
			String v = Version.class.getPackage().getImplementationVersion();
			if (v != null && !v.isBlank()) {
				return v;
			}
		} catch (Exception ignored) {
			// fall through
		}
		return "0.1.0";
	}
}
