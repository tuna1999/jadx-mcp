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

	private final SymbolResolver resolver;
	private final CodeCache codeCache;
	private final SearchService search;
	private final XrefService xref;

	public ApkSession(JadxDecompiler jadx, Path inputPath, long sizeBytes) {
		this.jadx = jadx;
		this.inputPath = inputPath;
		this.sizeBytes = sizeBytes;
		this.loadedAt = Instant.now();
		this.resolver = new SymbolResolver(jadx.getRoot());
		this.codeCache = new JadxCodeCache(jadx);
		this.search = new JadxSearchService(jadx.getRoot(), codeCache);
		this.xref = new JadxXrefService(resolver);
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
	}
}
