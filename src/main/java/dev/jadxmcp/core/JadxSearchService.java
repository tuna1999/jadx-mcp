package dev.jadxmcp.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import dev.jadxmcp.model.ClassEntry;
import dev.jadxmcp.model.MethodEntry;
import dev.jadxmcp.model.StringMatch;

/** Phase 1 {@link SearchService}: in-memory scan of loaded jadx structures. */
public final class JadxSearchService implements SearchService {

	private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

	private final RootNode root;
	private final CodeCache codeCache;

	public JadxSearchService(RootNode root, CodeCache codeCache) {
		this.root = root;
		this.codeCache = codeCache;
	}

	@Override
	public List<ClassEntry> searchClasses(String query, int limit) {
		String q = query.toLowerCase(Locale.ROOT);
		List<ClassEntry> result = new ArrayList<>();
		for (ClassNode cls : root.getClasses(true)) {
			String rawName = cls.getClassInfo().makeRawFullName();
			if (rawName.toLowerCase(Locale.ROOT).contains(q)
					|| cls.getClassInfo().getAliasFullName().toLowerCase(Locale.ROOT).contains(q)) {
				result.add(toEntry(cls));
			}
			if (result.size() >= limit) {
				break;
			}
		}
		result.sort(Comparator.comparing(ClassEntry::name));
		return result;
	}

	@Override
	public List<MethodEntry> searchMethods(String query, String classFilter, int limit) {
		String q = query.toLowerCase(Locale.ROOT);
		String cf = classFilter == null || classFilter.isBlank()
				? null
				: classFilter.toLowerCase(Locale.ROOT);
		List<MethodEntry> result = new ArrayList<>();
		for (ClassNode cls : root.getClasses(true)) {
			if (cf != null && !matchesClass(cls, cf)) {
				continue;
			}
			for (MethodNode mth : cls.getMethods()) {
				MethodInfo info = mth.getMethodInfo();
				if (info.getName().toLowerCase(Locale.ROOT).contains(q)
						|| info.getAlias().toLowerCase(Locale.ROOT).contains(q)
						|| info.getShortId().toLowerCase(Locale.ROOT).contains(q)) {
					result.add(new MethodEntry(
							SymbolResolver.methodId(mth),
							info.getAlias(),
							cls.getClassInfo().getAliasFullName(),
							info.getShortId(),
							mth.getAccessFlags().makeString(true)));
				}
				if (result.size() >= limit) {
					return result;
				}
			}
		}
		return result;
	}

	private boolean matchesClass(ClassNode cls, String cf) {
		return cls.getClassInfo().makeRawFullName().toLowerCase(Locale.ROOT).contains(cf)
				|| cls.getClassInfo().getAliasFullName().toLowerCase(Locale.ROOT).contains(cf);
	}

	@Override
	public List<StringMatch> searchStrings(String query, boolean caseSensitive, int limit, int maxClassesToScan) {
		String q = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
		Set<String> seen = new HashSet<>();
		List<StringMatch> result = new ArrayList<>();
		int scanned = 0;
		for (ClassNode cls : root.getClasses(false)) {
			if (scanned >= maxClassesToScan || result.size() >= limit) {
				break;
			}
			scanned++;
			String source;
			try {
				source = codeCache.getClassSource(cls);
			} catch (RuntimeException e) {
				continue; // class failed to decompile; skip it
			}
			if (source == null) {
				continue;
			}
			String[] lines = source.split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				Matcher m = STRING_LITERAL.matcher(lines[i]);
				while (m.find()) {
					String value = m.group(1);
					String cmp = caseSensitive ? value : value.toLowerCase(Locale.ROOT);
					if (cmp.contains(q) && seen.add(value + "@" + cls.getRawName())) {
					result.add(new StringMatch(
							value,
							SymbolResolver.classId(cls),
							cls.getClassInfo().getAliasFullName(),
							i + 1,
							null,
							null));
						if (result.size() >= limit) {
							return result;
						}
					}
				}
			}
		}
		return result;
	}

	private ClassEntry toEntry(ClassNode cls) {
		return new ClassEntry(
				SymbolResolver.classId(cls),
				cls.getClassInfo().getAliasFullName(),
				pkg(cls),
				cls.getClassInfo().isInner());
	}

	private String pkg(ClassNode cls) {
		try {
			return cls.getClassInfo().getPackage();
		} catch (RuntimeException e) {
			return "";
		}
	}
}
