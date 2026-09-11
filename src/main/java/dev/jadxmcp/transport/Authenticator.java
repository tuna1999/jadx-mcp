package dev.jadxmcp.transport;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Authentication extension point for the HTTP transport. Phase 1 ships without
 * an implementation (loopback-only by default); adding one later requires no
 * changes to tools or JADX core.
 */
@FunctionalInterface
public interface Authenticator {

	/** @return true when the request is allowed to reach the MCP endpoint. */
	boolean authenticate(HttpServletRequest request);
}
