package dev.jadxmcp.core;

import jadx.core.dex.nodes.ClassNode;

/**
 * {@link CodeCache} that persists decompiled sources in the Phase 2 index so a
 * later session on the same input (same content hash + jadx version) skips
 * decompilation entirely. First miss delegates to the wrapped cache, then
 * stores the result.
 */
public final class IndexedCodeCache implements CodeCache {

	private final CodeCache delegate;
	private final IndexStore store;

	public IndexedCodeCache(CodeCache delegate, IndexStore store) {
		this.delegate = delegate;
		this.store = store;
	}

	@Override
	public String getClassSource(ClassNode cls) {
		ClassNode top = cls.getTopParentClass();
		String classId = SymbolResolver.classId(top);
		String cached = store.cachedSource(classId);
		if (cached != null) {
			return cached;
		}
		String source = delegate.getClassSource(cls);
		if (source != null) {
			store.putSource(classId, source);
		}
		return source;
	}

	@Override
	public long decompiledCount() {
		return delegate.decompiledCount();
	}
}
