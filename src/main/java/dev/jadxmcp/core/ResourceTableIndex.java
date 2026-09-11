package dev.jadxmcp.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.core.xmlgen.ResourceStorage;
import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * Numeric resource-id lookup ({@code 0x7f...}) over the decoded
 * {@code resources.arsc} table. The table is decoded once per session via
 * jadx's own parser; inputs without a resource table yield an empty index.
 */
public final class ResourceTableIndex {

	private static final Logger LOG = LoggerFactory.getLogger(ResourceTableIndex.class);

	private static final ResourceTableIndex EMPTY = new ResourceTableIndex(Map.of(), Map.of());

	private final Map<Integer, ResourceEntry> byId;
	private final Map<String, Integer> byTypeKey; // "type/key" -> id

	private ResourceTableIndex(Map<Integer, ResourceEntry> byId, Map<String, Integer> byTypeKey) {
		this.byId = byId;
		this.byTypeKey = byTypeKey;
	}

	/** Index over explicitly supplied entries (test/injection path). */
	public static ResourceTableIndex of(Iterable<ResourceEntry> entries) {
		Map<Integer, ResourceEntry> byId = new HashMap<>();
		Map<String, Integer> byTypeKey = new HashMap<>();
		for (ResourceEntry e : entries) {
			byId.putIfAbsent(e.getId(), e);
			byTypeKey.putIfAbsent(e.getTypeName() + "/" + e.getKeyName(), e.getId());
		}
		return new ResourceTableIndex(Map.copyOf(byId), Map.copyOf(byTypeKey));
	}

	/**
	 * Decode {@code resources.arsc} from the loaded input. Returns an empty
	 * index (never null, never throws) when the input has no resource table or
	 * decoding fails.
	 */
	public static ResourceTableIndex decode(JadxDecompiler jadx, java.nio.file.Path apkPath) {
		try {
			ResourceFile arsc = jadx.getResources().stream()
					.filter(r -> r.getType() == ResourceType.ARSC)
					.findFirst()
					.orElse(null);
			if (arsc == null || apkPath == null) {
				return EMPTY;
			}
			byte[] raw = readEntry(apkPath, arsc.getOriginalName());
			if (raw.length == 0) {
				return EMPTY;
			}
			var parser = jadx.getResourcesLoader().decodeTable(arsc, new ByteArrayInputStream(raw));
			ResourceStorage storage = parser.getResStorage();
			return of(storage.getResources());
		} catch (Exception e) {
			LOG.debug("resource table decode failed; id lookup disabled", e);
			return EMPTY;
		}
	}

	private static byte[] readEntry(java.nio.file.Path apkPath, String entryName) {
		try (ZipFile zip = new ZipFile(apkPath.toFile())) {
			ZipEntry entry = zip.getEntry(entryName);
			if (entry == null) {
				return new byte[0];
			}
			try (var in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		} catch (IOException e) {
			return new byte[0];
		}
	}

	/**
	 * Find an entry by id string: hex ({@code 0x7f0e0001}, any case) or
	 * decimal. Returns null when unknown or malformed.
	 */
	public ResourceEntry findById(String id) {
		if (id == null) {
			return null;
		}
		Integer numeric = parseId(id);
		return numeric == null ? null : byId.get(numeric);
	}

	/** Id of {@code type/key} (e.g. {@code string/app_name}) or null. */
	public Integer idOf(String typeName, String keyName) {
		if (typeName == null || keyName == null) {
			return null;
		}
		return byTypeKey.get(typeName + "/" + keyName);
	}

	public boolean isEmpty() {
		return byId.isEmpty();
	}

	private static Integer parseId(String id) {
		String s = id.trim();
		try {
			if (s.startsWith("0x") || s.startsWith("0X")) {
				return Integer.parseUnsignedInt(s.substring(2), 16);
			}
			if (s.toLowerCase(Locale.ROOT).matches("[0-9a-f]{8}")) {
				return Integer.parseUnsignedInt(s, 16);
			}
			return Integer.parseUnsignedInt(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Human-stable rendering of an entry's value, or null when it has none. */
	public static String valueOf(ResourceEntry e) {
		if (e.getProtoValue() != null && e.getProtoValue().getValue() != null) {
			return e.getProtoValue().getValue();
		}
		if (e.getSimpleValue() != null) {
			return e.getSimpleValue().toString();
		}
		return null;
	}
}
