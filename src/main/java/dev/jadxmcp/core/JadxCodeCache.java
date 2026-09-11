package dev.jadxmcp.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.core.dex.nodes.ClassNode;

/**
 * {@link CodeCache} backed by jadx's own in-memory code cache. Decompilation
 * happens on first request per class and is reused for the lifetime of the
 * session.
 */
public final class JadxCodeCache implements CodeCache {

	private final JadxDecompiler jadx;
	private final Set<String> decompiled = ConcurrentHashMap.newKeySet();

	public JadxCodeCache(JadxDecompiler jadx) {
		this.jadx = jadx;
		// populate JavaClass wrappers for all top-level classes (cheap)
		jadx.getClasses();
	}

	@Override
	public String getClassSource(ClassNode cls) {
		ClassNode top = cls.getTopParentClass();
		decompiled.add(top.getRawName());
		JavaClass jc = top.getJavaNode();
		if (jc == null) {
			jc = jadx.searchJavaClassByOrigFullName(top.getClassInfo().getFullName());
		}
		if (jc == null) {
			throw new IllegalStateException("no JavaClass wrapper for " + top.getRawName());
		}
		// JavaClass.getCode() decompiles on demand; jadx caches the result internally
		return jc.getCode();
	}

	@Override
	public long decompiledCount() {
		return decompiled.size();
	}
}
