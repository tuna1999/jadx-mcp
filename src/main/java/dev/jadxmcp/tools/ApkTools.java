package dev.jadxmcp.tools;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jadx.api.ResourceFile;
import dev.jadxmcp.core.ApkSession;
import dev.jadxmcp.core.JadxService;
import dev.jadxmcp.model.ApkInfo;
import dev.jadxmcp.model.ManifestInfo;
import dev.jadxmcp.model.Page;
import dev.jadxmcp.model.PackageEntry;

/** load_apk, get_apk_info, list_packages, get_manifest. */
public final class ApkTools {

	private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneOffset.UTC);

	private final JadxService jadx;

	public ApkTools(JadxService jadx) {
		this.jadx = jadx;
	}

	public List<ToolDefinition> tools() {
		return List.of(
				loadApk(),
				getApkInfo(),
				listPackages(),
				getManifest());
	}

	private ToolDefinition loadApk() {
		return new ToolDefinition(
				"load_apk",
				"Load an APK/DEX/JAR file into the active analysis session. Replaces any previously loaded input.",
				"""
						{
						  "type": "object",
						  "properties": {
						    "path": { "type": "string", "description": "Absolute or relative path to the input file" }
						  },
						  "required": ["path"]
						}
						""",
				args -> {
					String path = Args.of(args).str("path");
					ApkSession session = jadx.load(java.nio.file.Path.of(path));
					return apkInfo(session);
				});
	}

	private ToolDefinition getApkInfo() {
		return new ToolDefinition(
				"get_apk_info",
				"Metadata about the currently loaded APK (class/resource/package counts, manifest package, jadx version).",
				"""
						{
						  "type": "object",
						  "properties": {}
						}
						""",
				args -> apkInfo(jadx.requireSession()));
	}

	private ToolDefinition listPackages() {
		return new ToolDefinition(
				"list_packages",
				"List Java packages with their class counts. Supports pagination and an optional name query.",
				"""
						{
						  "type": "object",
						  "properties": {
						    "query": { "type": "string", "description": "Optional substring filter on package name" },
						    "offset": { "type": "integer", "minimum": 0, "default": 0 },
						    "limit": { "type": "integer", "minimum": 1, "maximum": 1000, "default": 100 }
						  }
						}
						""",
				args -> {
					Args a = Args.of(args);
					ApkSession session = jadx.requireSession();
					String query = a.optStr("query");
					int offset = a.intOf("offset", 0, 0, Integer.MAX_VALUE);
					int limit = a.intOf("limit", 100, 1, 1000);
					List<PackageEntry> all = session.jadx().getPackages().stream()
							.filter(p -> query == null || p.getName().toLowerCase().contains(query.toLowerCase()))
							.map(p -> new PackageEntry(p.getName(), p.getClasses().size()))
							.sorted(java.util.Comparator.comparing(PackageEntry::name))
							.toList();
					List<PackageEntry> items = all.subList(Math.min(offset, all.size()),
							Math.min(offset + limit, all.size()));
					return Map.of("items", items, "page", Page.of(offset, limit, all.size()));
				});
	}

	private ToolDefinition getManifest() {
		return new ToolDefinition(
				"get_manifest",
				"Return AndroidManifest.xml decoded to readable XML, plus parsed package/version fields.",
				"""
						{
						  "type": "object",
						  "properties": {}
						}
						""",
				args -> {
					ApkSession session = jadx.requireSession();
					Optional<ResourceFile> manifest = session.manifestResource();
					if (manifest.isEmpty()) {
						throw new ToolException(dev.jadxmcp.model.ErrorCode.MANIFEST_NOT_FOUND,
								"input has no AndroidManifest.xml resource");
					}
					String xml;
					try {
						xml = manifest.get().loadContent().getText().getCodeStr();
					} catch (Exception e) {
						throw new ToolException(dev.jadxmcp.model.ErrorCode.DECOMPILATION_FAILED,
								"failed to decode AndroidManifest.xml", String.valueOf(e.getMessage()));
					}
					if (xml == null) {
						throw new ToolException(dev.jadxmcp.model.ErrorCode.MANIFEST_NOT_FOUND,
								"input has no decodable AndroidManifest.xml resource");
					}
					return parseManifest(xml);
				});
	}

	static ManifestInfo parseManifest(String xml) {
		String pkg = firstGroup(xml, "package=\"([^\"]+)\"");
		String versionName = firstGroup(xml, "android:versionName=\"([^\"]*)\"");
		String versionCodeStr = firstGroup(xml, "android:versionCode=\"(\\d+)\"");
		Integer versionCode = versionCodeStr == null ? null : Integer.valueOf(versionCodeStr);
		return new ManifestInfo(true, pkg, versionName, versionCode, xml);
	}

	private static String firstGroup(String text, String regex) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
		return m.find() ? m.group(1) : null;
	}

	ApkInfo apkInfo(ApkSession session) {
		boolean manifestPresent = session.manifestResource().isPresent();
		String manifestPackage = null;
		if (manifestPresent) {
			try {
				String xml = session.manifestResource().get().loadContent().getText().getCodeStr();
				manifestPackage = firstGroup(xml == null ? "" : xml, "package=\"([^\"]+)\"");
			} catch (Exception ignored) {
				// decoded lazily elsewhere; do not fail info because of it
			}
		}
		return new ApkInfo(
				true,
				session.inputPath().toString(),
				session.inputPath().getFileName().toString(),
				session.sizeBytes(),
				session.topLevelClassCount(),
				session.allClassNodes().size(),
				session.jadx().getPackages().size(),
				session.resources().size(),
				manifestPresent,
				manifestPackage,
				session.jadxVersion(),
				TS.format(session.loadedAt()));
	}
}
