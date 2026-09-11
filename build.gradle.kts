import java.io.File
import java.util.zip.ZipFile

plugins {
    java
    application
}

// Version resolution: -PappVersion=... > tag ref (v1.2.3) > JADXMCP_VERSION env > default.
// Keeps local builds stable while CI/release builds carry the tag version.
fun resolveAppVersion(): String {
	providers.gradleProperty("appVersion").orNull?.takeIf { it.isNotBlank() }?.let { return it }
	providers.environmentVariable("GITHUB_REF_NAME").orNull?.let { ref ->
		val v = ref.removePrefix("v")
		if (Regex("""^\d+(\.\d+){0,3}(-[\w.]+)?$""").matches(v)) return v
	}
	providers.environmentVariable("JADXMCP_VERSION").orNull?.takeIf { it.isNotBlank() }?.let { return it }
	return "0.1.0"
}

group = "dev.jadxmcp"
version = resolveAppVersion()

repositories {
    mavenCentral()
    google()
}

java {
    toolchain {
        // Runs on the installed JDK; bytecode target is pinned via --release below.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("io.github.skylot:jadx-core:1.5.6")
    implementation("io.github.skylot:jadx-dex-input:1.5.6")
    implementation("io.github.skylot:jadx-java-input:1.5.6")
    implementation("io.github.skylot:jadx-kotlin-metadata:1.5.6")

    implementation("io.modelcontextprotocol.sdk:mcp:2.0.1")
    implementation("tools.jackson.core:jackson-databind:3.1.4")

    implementation("org.xerial:sqlite-jdbc:3.53.4.0")

    implementation("org.eclipse.jetty:jetty-server:12.0.39")
    implementation("org.eclipse.jetty.ee10:jetty-ee10-servlet:12.0.39")

    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("com.android.tools:r8:9.4.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.jadxmcp.Main"
}

// The plain jar would collide with fatJar's versioned output path; keep it distinct.
tasks.jar {
    archiveClassifier = "thin"
}

tasks.compileJava {
    options.release = 17
    options.encoding = "UTF-8"
}
tasks.compileTestJava {
    options.release = 17
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    maxHeapSize = "2g"
    // Integration tests spawn the packaged fat jar.
    dependsOn(tasks.named("fatJar"))
    systemProperty("jadxmcp.jar", tasks.named<Jar>("fatJar").flatMap { it.archiveFile }.get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------
// Fat jar: single-file runnable `java -jar jadx-mcp.jar <mode>`.
// META-INF/services files from all dependencies are merged so ServiceLoader
// based providers (SLF4J, MCP JSON mapper, JADX plugins) survive shading.
// ---------------------------------------------------------------------------
val mergeServiceFiles = tasks.register("mergeServiceFiles") {
    val outputDir = layout.buildDirectory.dir("merged-services")
    inputs.files(configurations.runtimeClasspath)
    outputs.dir(outputDir)
    doLast {
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        val services = sortedMapOf<String, MutableList<String>>()
        configurations.runtimeClasspath.get().files.forEach { f ->
            if (f.isFile && f.extension == "jar") {
                ZipFile(f).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                        .forEach { entry ->
                            val name = entry.name.removePrefix("META-INF/services/")
                            val lines = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                                .lines().filter { it.isNotBlank() }
                            services.getOrPut(name) { mutableListOf() }.addAll(lines)
                        }
                }
            }
        }
        services.forEach { (name, entries) ->
            val target = File(outDir, "META-INF/services/$name")
            target.parentFile.mkdirs()
            target.writeText(entries.distinct().joinToString("\n") + "\n")
        }
    }
}

val fatJar = tasks.register<Jar>("fatJar") {
    archiveFileName = "jadx-mcp-${project.version}.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "dev.jadxmcp.Main"
        attributes["Implementation-Title"] = "jadx-mcp"
        attributes["Implementation-Version"] = project.version
    }
    from(mergeServiceFiles)
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("META-INF/services/**")
        exclude("META-INF/versions/**")
        exclude("module-info.class")
        exclude("LICENSE*", "README*", "NOTICE*")
    }
}

tasks.build {
    dependsOn(fatJar)
}
