package dev.jadxmcp.core;

import jadx.core.dex.nodes.ClassNode;

/**
 * Cache of decompiled class sources. Backed by jadx's in-memory code cache in
 * Phase 1; Phase 2 can swap in a disk/SQLite backed implementation without
 * changing tool code.
 */
public interface CodeCache {

	/**
	 * Return the decompiled Java source of the class, decompiling it on demand.
	 *
	 * @throws jadx.core.utils.exceptions.JadxRuntimeException on decompilation failure
	 */
	String getClassSource(ClassNode cls);

	/** Number of distinct classes decompiled so far in this session. */
	long decompiledCount();
}
