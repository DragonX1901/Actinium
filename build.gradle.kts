import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.security.MessageDigest
import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    `java-library`
    `maven-publish`
    idea
    id("net.neoforged.moddev") version "2.0.143"
}

// ---------------------------------------------------------------------------
// gradle.properties bindings
// ---------------------------------------------------------------------------
val mod_id: String by project
val mod_name: String by project
val mod_license: String by project
val mod_version: String by project
val mod_group_id: String by project
val mod_authors: String by project
val mod_description: String by project

val minecraft_version: String by project
val minecraft_version_range: String by project
val neo_version: String by project
val neo_version_range: String by project
val loader_version_range: String by project
val parchment_minecraft_version: String by project
val parchment_mappings_version: String by project

// Must match the LWJGL core version Minecraft 1.21.1 ships. Mixing a different
// core across lwjgl-vulkan / lwjgl-vma / lwjgl-shaderc produces
// UnsatisfiedLinkError at runtime because org.lwjgl.system.* is shared.
val lwjgl_version: String by project

version = mod_version
group = mod_group_id

base { archivesName = mod_id }

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories {
    mavenLocal()
    mavenCentral()
}

// ---------------------------------------------------------------------------
// Native classifiers for the host running ./gradlew runClient.
// Production builds ship every classifier through jarJar (see below).
// ---------------------------------------------------------------------------
val hostNatives: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("win") -> if (arch.contains("aarch64")) "natives-windows-arm64" else "natives-windows"
        os.contains("mac") -> if (arch.contains("aarch64")) "natives-macos-arm64" else "natives-macos"
        else -> if (arch.contains("aarch64")) "natives-linux-arm64" else "natives-linux"
    }
}

neoForge {
    version = neo_version

    parchment {
        mappingsVersion = parchment_mappings_version
        minecraftVersion = parchment_minecraft_version
    }

    // Actinium rewrites the render backend; access to a few private vanilla
    // fields (Window.window, RenderTarget.frameBufferId) is unavoidable.
    accessTransformers.from("src/main/resources/META-INF/accesstransformer.cfg")

    runs {
        register("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
            // Mixin verbosity while bootstrapping the Vulkan swap of Window.
            jvmArgument("-Dmixin.debug.export=true")
            // Belt and braces: ActiniumMixinPlugin sets this during mixin bootstrap, but a
            // dev launch should not depend on that hook to test the rest of the engine.
            jvmArgument("-Dorg.lwjgl.system.stackSize=1024")
            // Vulkan validation layers in dev only. Costs ~30% CPU frame time.
            systemProperty("actinium.validation", "true")

            // Boot straight into a save instead of clicking through menus:
            //   ./gradlew runClient -PquickPlay="New World"
            // Reproducing a rendering bug needs the client on screen, but it does not need a
            // human driving the main menu, and synthetic clicks aimed at someone else's
            // desktop are worse than a launch flag.
            val quickPlay = project.findProperty("quickPlay") as String?
            if (quickPlay != null) {
                programArguments.addAll("--quickPlaySingleplayer", quickPlay)
            }

            // Exercise the OpenGL compatibility layer on the paths the backend refuses:
            //   ./gradlew runClient -PglSelfTest
            // Most of those paths are ones no installed mod happens to take, so a run that
            // reaches a world proves nothing about them. This makes the calls directly.
            if (project.hasProperty("selfTestGlBind")) {
                systemProperty("actinium.selfTestGlBind", "true")
            }
            if (project.hasProperty("dumpTargets")) {
                systemProperty("actinium.dumpTargets", "true")
            }
            if (project.hasProperty("glSelfTest")) {
                systemProperty("actinium.glSelfTest", "true")
            }

            // Run Actinium as the renderer instead of as a layer over VulkanMod:
            //   ./gradlew runClient -Prenderer
            // VulkanMod must not be installed when this is on; two backends cannot both own
            // the window surface.
            if (project.hasProperty("renderer")) {
                systemProperty("actinium.renderer", "true")
            }

            // Name the caller that freed each Vulkan image, for chasing a use-after-free:
            //   ./gradlew runClient -PtraceFrees
            if (project.hasProperty("traceFrees")) {
                systemProperty("actinium.traceFrees", "true")
            }

            // Turn the mixin preflight off, to test whether a failure downstream is its doing:
            //   ./gradlew runClient -PnoPreflight
            if (project.hasProperty("noPreflight")) {
                systemProperty("actinium.noPreflight", "true")
            }
        }
        // The packaged jar, loaded the way a player loads it: from the mods folder, with
        // nothing from this source tree on the classpath. It is the only run that exercises
        // the embedded renderer, because a dev run loads Actinium from classes and never
        // unpacks a jar-in-jar.
        //
        //   ./gradlew build && cp build/libs/actinium-*.jar run-prod/mods/
        //   ./gradlew prodClient
        register("prodClient") {
            client()
            gameDirectory = file("run-prod")
            loadedMods.empty()
            jvmArgument("-Dorg.lwjgl.system.stackSize=1024")
            val quickPlay = project.findProperty("quickPlay") as String?
            if (quickPlay != null) {
                programArguments.addAll("--quickPlaySingleplayer", quickPlay)
            }
        }
        register("server") {
            server()
            programArgument("--nogui")
        }
        // Official NeoForge headless run. Loads every mod, applies every mixin and runs any
        // registered game tests without a window, which is the only part of this mod that can
        // be checked without a display.
        register("gameTestServer") {
            type = "gameTestServer"
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
            // Its own directory, so it does not pick up the client mods in run/mods.
            // VulkanMod is client-only and its mixin plugin touches org.lwjgl.system on load,
            // which kills a dedicated server before it starts. That is expected of a renderer
            // and not something to work around; this check exists to prove Actinium itself
            // loads and applies cleanly with nothing else present.
            gameDirectory = file("run-gametest")
        }

        register("data") {
            data()
            programArguments.addAll(
                "--mod", mod_id,
                "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath,
            )
        }
        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        register(mod_id) { sourceSet(sourceSets.main.get()) }
    }
}

sourceSets.main.get().resources.srcDir("src/generated/resources")

dependencies {
    // ---- Vulkan, VMA and shaderc ------------------------------------------
    //
    // compileOnly, deliberately. VulkanMod is a required dependency and it extracts these
    // modules from its own jar at startup, registering them as NeoForge mod files
    // (vulkan_lwjgl, vma, vulkan_shaderc). Shipping a second copy is not redundant, it is
    // fatal:
    //
    //   ResolutionException: Modules vulkan_lwjgl and org.lwjgl.vulkan
    //                        export package org.lwjgl.vulkan.video
    //
    // That also means no jarJar block and no native classifiers. The published jar is pure
    // Java and a few hundred kilobytes.
    compileOnly("org.lwjgl:lwjgl-vulkan:$lwjgl_version")
    compileOnly("org.lwjgl:lwjgl-vma:$lwjgl_version")
    compileOnly("org.lwjgl:lwjgl-shaderc:$lwjgl_version")

    // lwjgl-opengl and LWJGL core ship with Minecraft; referenced for GL constants and the
    // bridge context.
    compileOnly("org.lwjgl:lwjgl-opengl:$lwjgl_version")

    // ---- Renderer mode ----------------------------------------------------
    //
    // Renderer mode has no VulkanMod to supply the Vulkan modules, so the dev run adds them
    // itself. Only under -Prenderer: with VulkanMod installed they arrive as mod files, and a
    // second copy on the classpath is fatal —
    //
    //   ResolutionException: Modules vulkan_lwjgl and org.lwjgl.vulkan
    //                        export package org.lwjgl.vulkan
    //
    // The shipped jar carries them as mod files instead; see packVulkanLibs.
    if (project.hasProperty("renderer")) {
        "clientAdditionalRuntimeClasspath"("org.lwjgl:lwjgl-vulkan:$lwjgl_version")
        "clientAdditionalRuntimeClasspath"("org.lwjgl:lwjgl-vma:$lwjgl_version")
        "clientAdditionalRuntimeClasspath"("org.lwjgl:lwjgl-vma:$lwjgl_version:$hostNatives")
        "clientAdditionalRuntimeClasspath"("org.lwjgl:lwjgl-shaderc:$lwjgl_version")
        "clientAdditionalRuntimeClasspath"("org.lwjgl:lwjgl-shaderc:$lwjgl_version:$hostNatives")
    }

    // ---- Bytecode interception --------------------------------------------
    // ASM comes from the NeoForge dev bundle; compileOnly so no second copy is shaded.
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-tree:9.7")
    compileOnly("org.ow2.asm:asm-commons:9.7")
}

// ---------------------------------------------------------------------------
// The Vulkan renderer, shipped inside the jar
//
// Actinium is what a player installs, and installing it has to be enough to run the game on
// Vulkan. The renderer is VulkanModNeoForge, embedded unmodified through NeoForge's jar-in-jar
// mechanism rather than copied into this source tree: it is LGPL-3.0, and keeping it a separate
// jar is what lets a user replace it with their own build, which that licence requires.
//
// A newer copy installed in the mods folder wins over the embedded one, because FML picks the
// highest version among candidates for the same mod id.
// ---------------------------------------------------------------------------
val rendererVersion = "1.0.0"
val rendererSha256 = "e5efe9dd281415e3e6794ac366302fa5209b2384438730c735b3719d3f43f1ff"
val rendererUrl =
    "https://github.com/yiyuyan/VulkanModNeoForge/releases/download/1.21neo-1.0/vulkanmod-$rendererVersion.jar"
val rendererJar = layout.buildDirectory.file("renderer/vulkanmod-$rendererVersion.jar")
val rendererMetadataFile = layout.buildDirectory.file("renderer/metadata.json")

val downloadRenderer by tasks.registering {
    description = "Fetches the VulkanModNeoForge release that ships inside the Actinium jar."
    val target = rendererJar
    val url = rendererUrl
    val expected = rendererSha256
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        // The hash is pinned, so a mirror, a proxy or a re-tagged release cannot quietly change
        // what ships under Actinium's name.
        fun hash(): String {
            if (!file.exists()) {
                return ""
            }
            val bytes = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val text = StringBuilder()
            for (byte in bytes) {
                text.append(String.format("%02x", byte))
            }
            return text.toString()
        }
        if (hash() != expected) {
            URI.create(url).toURL().openStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val actual = hash()
        check(actual == expected) {
            "Renderer jar does not match its pinned hash.\n  expected $expected\n  actual   $actual"
        }
    }
}

// LWJGL's Vulkan bindings, wrapped as loadable mod files.
//
// Minecraft ships LWJGL without vulkan, vma or shaderc, and the renderer does not carry them
// either, so on its own it dies at NoClassDefFoundError: org/lwjgl/vulkan/VkApplicationInfo.
// FML loads any jar that has a mods.toml, so each artifact gets one written into it and rides
// along in the same jar-in-jar directory as the renderer.
val vulkanLibs: Configuration by configurations.creating

// Minecraft already provides LWJGL core; a second copy breaks module resolution.
configurations.named("vulkanLibs") { isTransitive = false }

dependencies {
    for (platform in listOf(null, "natives-linux", "natives-windows", "natives-macos",
                            "natives-macos-arm64")) {
        val suffix = if (platform == null) "" else ":$platform"
        // lwjgl-vulkan ships natives for macOS only; elsewhere it uses the system loader.
        if (platform == null || platform.contains("macos")) {
            vulkanLibs("org.lwjgl:lwjgl-vulkan:$lwjgl_version$suffix")
        }
        vulkanLibs("org.lwjgl:lwjgl-vma:$lwjgl_version$suffix")
        vulkanLibs("org.lwjgl:lwjgl-shaderc:$lwjgl_version$suffix")
    }
}

val packVulkanLibs by tasks.registering {
    description = "Writes a mods.toml into each LWJGL Vulkan artifact so FML will load it."
    val sources: FileCollection = files(vulkanLibs)
    val outputDir = layout.buildDirectory.dir("vulkan-libs")
    val libraryVersion = lwjgl_version
    inputs.files(sources)
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        for (source in sources.files) {
            val fileName = source.name
            val base = when {
                fileName.startsWith("lwjgl-vulkan") -> "vulkan_lwjgl"
                fileName.startsWith("lwjgl-vma") -> "vma"
                else -> "vulkan_shaderc"
            }
            val platform = Regex("natives-([a-z0-9-]+)\\.jar").find(fileName)?.groupValues?.get(1)
            val modId = if (platform == null) base else base + "_" + platform.replace("-", "")
            val displayName = source.name.removeSuffix(".jar")
            val descriptor = listOf(
                "modLoader = \"lowcodefml\"",
                "loaderVersion = \"[4,)\"",
                "license = \"BSD-3-Clause\"",
                "",
                "[[mods]]",
                "modId = \"$modId\"",
                "version = \"$libraryVersion\"",
                "displayName = \"$displayName\"",
            ).joinToString("\n")
            val destination = File(target, source.name)
            ZipOutputStream(destination.outputStream().buffered()).use { out ->
                ZipInputStream(source.inputStream().buffered()).use { input ->
                    var entry = input.nextEntry
                    while (entry != null) {
                        if (entry.name != "META-INF/neoforge.mods.toml") {
                            out.putNextEntry(ZipEntry(entry.name))
                            input.copyTo(out)
                            out.closeEntry()
                        }
                        entry = input.nextEntry
                    }
                }
                out.putNextEntry(ZipEntry("META-INF/neoforge.mods.toml"))
                out.write(descriptor.toByteArray())
                out.closeEntry()
            }
        }
    }
}

val rendererMetadata by tasks.registering {
    description = "Writes the jar-in-jar manifest FML reads to find the embedded mods."
    val output = rendererMetadataFile
    val version = rendererVersion
    val libraries: FileCollection = files(vulkanLibs)
    val libraryVersion = lwjgl_version
    outputs.file(output)
    doLast {
        fun entry(group: String, artifact: String, artifactVersion: String, path: String) = """
            |    {
            |      "identifier": { "group": "$group", "artifact": "$artifact" },
            |      "version": { "range": "[$artifactVersion,)", "artifactVersion": "$artifactVersion" },
            |      "path": "META-INF/jarjar/$path",
            |      "isObfuscated": false
            |    }
        """.trimMargin()

        val entries = mutableListOf(
            entry("cn.ksmcbrigade.vulkan", "vulkanmod", version, "vulkanmod-$version.jar")
        )
        for (library in libraries.files.sortedBy { it.name }) {
            entries += entry("org.lwjgl", library.name.removeSuffix(".jar"), libraryVersion,
                library.name)
        }
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText("{\n  \"jars\": [\n" + entries.joinToString(",\n") + "\n  ]\n}\n")
    }
}

tasks.jar {
    dependsOn(downloadRenderer, packVulkanLibs, rendererMetadata)
    from(rendererJar) { into("META-INF/jarjar") }
    from(layout.buildDirectory.dir("vulkan-libs")) { into("META-INF/jarjar") }
    from(rendererMetadataFile) { into("META-INF/jarjar") }
}

// ---------------------------------------------------------------------------
// The smoke test runs outside the game, so it needs the real artifacts rather
// than the compileOnly stubs, natives included.
// ---------------------------------------------------------------------------
val smokeTestRuntime: Configuration by configurations.creating

dependencies {
    smokeTestRuntime("org.lwjgl:lwjgl:$lwjgl_version")
    smokeTestRuntime("org.lwjgl:lwjgl:$lwjgl_version:$hostNatives")
    smokeTestRuntime("org.lwjgl:lwjgl-glfw:$lwjgl_version")
    smokeTestRuntime("org.lwjgl:lwjgl-glfw:$lwjgl_version:$hostNatives")
    smokeTestRuntime("org.lwjgl:lwjgl-vulkan:$lwjgl_version")
    smokeTestRuntime("org.lwjgl:lwjgl-vma:$lwjgl_version")
    smokeTestRuntime("org.lwjgl:lwjgl-vma:$lwjgl_version:$hostNatives")
    smokeTestRuntime("org.lwjgl:lwjgl-shaderc:$lwjgl_version")
    smokeTestRuntime("org.lwjgl:lwjgl-shaderc:$lwjgl_version:$hostNatives")
    smokeTestRuntime("org.ow2.asm:asm:9.7")
    smokeTestRuntime("org.ow2.asm:asm-tree:9.7")
}

// ---------------------------------------------------------------------------
// Standalone verification. Runs the Vulkan bootstrap, the GLSL -> SPIR-V path and
// the bytecode transformer without launching Minecraft, so a broken driver or a
// bad shader patch fails in seconds instead of at a black game window.
//
//   ./gradlew smokeTest
// ---------------------------------------------------------------------------
tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Verifies Vulkan init, shader compilation and GL call redirection without Minecraft."
    mainClass = "net.actinium.dev.VulkanSmokeTest"
    classpath = sourceSets.main.get().runtimeClasspath + smokeTestRuntime
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    // Vulkan needs the loader on the library path and a surface to present to.
    systemProperty("actinium.validation", "true")
    // See ActiniumMixinPlugin: LWJGL reads this once, into a static final, when MemoryStack
    // loads. 64 KB is not enough to enumerate device extensions on a multi-GPU machine.
    systemProperty("org.lwjgl.system.stackSize", "1024")
    // CI has no GPU and no display, so the Vulkan section is skipped there. The pixel
    // converter, bytecode and shader checks still run and are the ones most likely to
    // regress from an ordinary edit.
    systemProperty("actinium.headless",
            (project.findProperty("actinium.headless") as String? ?: "false"))
}

tasks.register<JavaExec>("shaderCorpus") {
    group = "verification"
    description = "Converts every GLSL program in the given jars, or on the classpath, to SPIR-V."
    mainClass = "net.actinium.dev.ShaderCorpus"
    classpath = sourceSets.main.get().runtimeClasspath + smokeTestRuntime
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    systemProperty("org.lwjgl.system.stackSize", "1024")
    (project.findProperty("dump") as String?)?.let { systemProperty("actinium.dumpShaders", it) }
    args = (project.findProperty("jars") as String? ?: "").split(",").filter { it.isNotBlank() }
}

tasks.register<JavaExec>("renderTest") {
    group = "verification"
    description = "Draws through the GL layer on an offscreen Vulkan device and checks the pixels."
    mainClass = "net.actinium.dev.HeadlessRenderTest"
    classpath = sourceSets.main.get().runtimeClasspath + smokeTestRuntime
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    systemProperty("org.lwjgl.system.stackSize", "1024")
    systemProperty("actinium.validation",
            (project.findProperty("validation") as String? ?: "true"))
    val softwareIcd = file("/usr/share/vulkan/icd.d/lvp_icd.json")
    if (!file("/dev/dri").exists() && softwareIcd.exists()) {
        environment("VK_ICD_FILENAMES", softwareIcd.absolutePath)
    }
}

tasks.register("listConfigs") {
    val names = configurations.names.sorted()
    doLast { names.forEach(::println) }
}

tasks.register<JavaExec>("convertMods") {
    group = "verification"
    description = "Rewrites a mod jar's OpenGL calls to Actinium and caches the result."
    mainClass = "net.actinium.interop.GlJarConverter"
    classpath = sourceSets.main.get().runtimeClasspath + smokeTestRuntime
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    args = (project.findProperty("jars") as String? ?: "run/mods").split(",")
        .filter { it.isNotBlank() }
}

tasks.register<JavaExec>("scanMods") {
    group = "verification"
    description = "Reports how much of a mod jar's OpenGL usage the interop layer covers."
    mainClass = "net.actinium.dev.ModScanner"
    classpath = sourceSets.main.get().runtimeClasspath + smokeTestRuntime
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    args = (project.findProperty("jars") as String? ?: "").split(",").filter { it.isNotBlank() }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events(TestLogEvent.FAILED, TestLogEvent.SKIPPED) }
}

// The mod jar must be recognised by ModLauncher as carrying a launch plugin
// service (the GL redirect transformer runs before the game layer exists).
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Specification-Title" to mod_name,
            "Specification-Vendor" to mod_authors,
            "Specification-Version" to "1",
            "Implementation-Title" to mod_name,
            "Implementation-Version" to mod_version,
            "Implementation-Vendor" to mod_authors,
        )
    }
}

val generateModMetadata by tasks.registering(ProcessResources::class) {
    val replaceProperties = mapOf(
        "minecraft_version" to minecraft_version,
        "minecraft_version_range" to minecraft_version_range,
        "neo_version" to neo_version,
        "neo_version_range" to neo_version_range,
        "loader_version_range" to loader_version_range,
        "mod_id" to mod_id,
        "mod_name" to mod_name,
        "mod_license" to mod_license,
        "mod_version" to mod_version,
        "mod_authors" to mod_authors,
        "mod_description" to mod_description,
    )
    inputs.properties(replaceProperties)
    expand(replaceProperties)
    from("src/main/templates")
    into(layout.buildDirectory.dir("generated/sources/modMetadata"))
}

sourceSets.main.get().resources.srcDir(generateModMetadata)
neoForge.ideSyncTask(generateModMetadata)

publishing {
    publications {
        register<MavenPublication>("mavenJava") { from(components["java"]) }
    }
    repositories {
        maven { url = uri("file://${project.projectDir}/repo") }
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}
