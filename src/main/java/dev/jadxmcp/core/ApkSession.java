package dev.jadxmcp.core;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

/**
 * One loaded input (APK/DEX/JAR) and everything derived from it. Designed so
 * that multi-session support can be added later by holding several sessions.
 */
public final class ApkSession implements AutoCloseable {

	private final JadxDecompiler jadx;
	private final Path inputPath;
	private final long sizeBytes;
	private final Instant loadedAt;

	private final IndexStore index;
	private final SymbolResolver resolver;
	private final ResourceTableIndex resourceTable;
	private final CodeCache codeCache;
	private final SearchService search;
	private final XrefService xref;

	public ApkSession(JadxDecompiler jadx, Path inputPath, long sizeBytes) {
		this(jadx, inputPath, sizeBytes, null);
	}

	/** Session with an optional Phase 2 index store (null = Phase 1 services). */
	public ApkSession(JadxDecompiler jadx, Path inputPath, long sizeBytes, IndexStore index) {
		this.jadx = jadx;
		this.inputPath = inputPath;
		this.sizeBytes = sizeBytes;
		this.loadedAt = Instant.now();
		this.resolver = new SymbolResolver(jadx.getRoot());
		this.resourceTable = ResourceTableIndex.decode(jadx, inputPath);
		this.codeCache = index != null ? new IndexedCodeCache(new JadxCodeCache(jadx), index) : new JadxCodeCache(jadx);
		this.search = index != null
				? new IndexedSearchService(new JadxSearchService(jadx.getRoot(), codeCache), index)
				: new JadxSearchService(jadx.getRoot(), codeCache);
		this.xref = index != null ? new IndexedXrefService(new JadxXrefService(resolver), index)
				: new JadxXrefService(resolver);
		this.index = index;
	}

	public JadxDecompiler jadx() {
		return jadx;
	}

	public RootNode root() {
		return jadx.getRoot();
	}

	public Path inputPath() {
		return inputPath;
	}

	public Instant loadedAt() {
		return loadedAt;
	}

	public SymbolResolver resolver() {
		return resolver;
	}

	public CodeCache codeCache() {
		return codeCache;
	}

	public SearchService search() {
		return search;
	}

	public XrefService xref() {
		return xref;
	}

	/** Phase 2 index store backing this session, or null when disabled. */
	public IndexStore indexStore() {
		return index;
	}

	/** Decoded resource table for numeric-id lookup (empty when absent). */
	public ResourceTableIndex resourceTable() {
		return resourceTable;
	}

	/** Top-level classes (without inner classes), in jadx order. */
	public List<JavaClass> topLevelClasses() {
		return jadx.getClasses();
	}

	/** All class nodes including inner classes. */
	public List<ClassNode> allClassNodes() {
		return root().getClasses(true);
	}

	public int topLevelClassCount() {
		return jadx.getClasses().size();
	}

	public List<ResourceFile> resources() {
		return jadx.getResources();
	}

	public Optional<ResourceFile> manifestResource() {
		return resources().stream()
				.filter(r -> r.getType() == ResourceType.MANIFEST)
				.findFirst();
	}

	public long sizeBytes() {
		return sizeBytes;
	}

	public String jadxVersion() {
		return JadxDecompiler.getVersion();
	}

	@Override
	public void close() {
		jadx.close();
		if (index != null) {
			index.close();
		}
	}
}
