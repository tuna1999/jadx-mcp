package dev.jadxmcp.fixture;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Builds the tiny legal test APK used by unit and integration tests:
 * fixture Java sources -> javac (--release 8) -> D8 (classes.dex) -> zip with a
 * binary AndroidManifest.xml (via {@link AxmlWriter}) and a few resources.
 * Cached under build/fixtures/test.apk; regenerated when missing.
 */
public final class FixtureApk {

	public static final String PACKAGE = "dev.jadxmcp.fixture";
	public static final String CRYPTO_UTIL_ID = "Ldev/jadxmcp/fixture/CryptoUtil;";
	public static final String ENCODE_METHOD_ID = "Ldev/jadxmcp/fixture/CryptoUtil;->encode([B[B)[B";
	public static final String API_ENDPOINT = "https://api.fixture.example.com/v1/data";

	private static Path cached;

	private FixtureApk() {
	}

	public static synchronized Path apk() {
		if (cached != null && Files.exists(cached)) {
			return cached;
		}
		try {
			Path target = Path.of("build", "fixtures", "test.apk").toAbsolutePath();
			Files.createDirectories(target.getParent());
			buildApk(target);
			cached = target;
			return target;
		} catch (Exception e) {
			throw new IllegalStateException("failed to build fixture APK", e);
		}
	}

	private static void buildApk(Path target) throws Exception {
		Path work = Files.createTempDirectory("jadx-mcp-fixture");
		try {
			List<InMemorySource> sources = readFixtureSources();
			Path classesDir = compile(sources, work);
			Path dex = dex(classesDir, work);
			byte[] manifest = manifest();
			zip(target, dex, manifest);
		} finally {
			deleteRecursively(work);
		}
	}

	// ------------------------------------------------------------------ javac

	private record InMemorySource(String className, String source) {
	}

	private static final class StringSource extends SimpleJavaFileObject {
		private final String code;

		StringSource(URI uri, String code) {
			super(uri, Kind.SOURCE);
			this.code = code;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) {
			return code;
		}
	}

	private static List<InMemorySource> readFixtureSources() throws IOException {
		Path srcRoot = Path.of("src", "test", "fixture-src");
		List<InMemorySource> sources = new ArrayList<>();
		Files.walkFileTree(srcRoot, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (file.toString().endsWith(".java")) {
					String rel = srcRoot.relativize(file).toString().replace('\\', '/');
					String cls = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
					try {
						sources.add(new InMemorySource(cls, Files.readString(file)));
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
				return FileVisitResult.CONTINUE;
			}
		});
		if (sources.isEmpty()) {
			throw new IllegalStateException("no fixture sources found under " + srcRoot.toAbsolutePath());
		}
		return sources;
	}

	private static Path compile(List<InMemorySource> sources, Path work) throws IOException {
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) {
			throw new IllegalStateException("tests must run on a JDK (system java compiler missing)");
		}
		Path out = Files.createDirectory(work.resolve("classes"));
		StringWriter err = new StringWriter();
		try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			List<javax.tools.JavaFileObject> units = new ArrayList<>();
			for (InMemorySource s : sources) {
				units.add(new StringSource(
						URI.create("string:///" + s.className().replace('.', '/') + ".java"), s.source()));
			}
			boolean ok = javac.getTask(new PrintWriter(err, true), fm, null,
					List.of("--release", "8", "-Xlint:-options", "-d", out.toString()), null, units).call();
			if (!ok) {
				throw new IllegalStateException("fixture javac failed:\n" + err);
			}
		}
		return out;
	}

	// --------------------------------------------------------------------- D8

	private static Path dex(Path classesDir, Path work) throws Exception {
		Path dexOut = Files.createDirectory(work.resolve("dex"));
		List<String> args = new ArrayList<>();
		args.add("--release");
		args.add("--min-api");
		args.add("26");
		args.add("--output");
		args.add(dexOut.toString());
		try (var walk = Files.walk(classesDir)) {
			walk.filter(p -> p.toString().endsWith(".class"))
					.map(Path::toAbsolutePath)
					.map(Path::toString)
					.forEach(args::add);
		}
		Class<?> d8 = Class.forName("com.android.tools.r8.D8");
		d8.getMethod("main", String[].class).invoke(null, (Object) args.toArray(new String[0]));
		Path dex = dexOut.resolve("classes.dex");
		if (!Files.exists(dex)) {
			throw new IllegalStateException("D8 did not produce classes.dex");
		}
		return dex;
	}

	// -------------------------------------------------------------- manifest

	private static byte[] manifest() throws IOException {
		AxmlWriter axml = AxmlWriter.document("manifest", "android", "http://schemas.android.com/apk/res/android");
		AxmlWriter.Element manifest = axml.root();
		manifest.attr("package", PACKAGE);
		manifest.attr("android:versionCode", 1);
		manifest.attr("android:versionName", "1.0");
		manifest.child("uses-permission").attr("android:name", "android.permission.INTERNET");
		AxmlWriter.Element app = manifest.child("application");
		app.attr("android:label", "FixtureApp");
		app.attr("android:allowBackup", true);
		AxmlWriter.Element activity = app.child("activity");
		activity.attr("android:name", "dev.jadxmcp.fixture.MainActivity");
		activity.attr("android:exported", true);
		AxmlWriter.Element filter = activity.child("intent-filter");
		filter.child("action").attr("android:name", "android.intent.action.MAIN");
		filter.child("category").attr("android:name", "android.intent.category.LAUNCHER");
		return axml.toBytes();
	}

	// -------------------------------------------------------------------- zip

	private static void zip(Path target, Path dex, byte[] manifest) throws IOException {
		try (OutputStream os = Files.newOutputStream(target);
				ZipOutputStream zip = new ZipOutputStream(os)) {
			put(zip, "AndroidManifest.xml", manifest);
			put(zip, "classes.dex", Files.readAllBytes(dex));
			put(zip, "assets/notes.txt", "fixture notes - legal test content\n".getBytes(StandardCharsets.UTF_8));
			put(zip, "assets/config.json", "{\"env\":\"test\",\"retries\":3}".getBytes(StandardCharsets.UTF_8));
			put(zip, "res/raw/data.bin", new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 });
		}
	}

	private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(data);
		zip.closeEntry();
	}

	private static void deleteRecursively(Path dir) throws IOException {
		Files.walkFileTree(dir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
				Files.deleteIfExists(d);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
