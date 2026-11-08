import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.security.MessageDigest
import java.io.File
import java.net.URI
import java.util.jar.Attributes
import java.util.jar.Manifest
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

            // Count where each frame's draws land, to tell an empty frame from a misdirected one:
            //   ./gradlew runClient -Prenderer -PframeStats
            if (project.hasProperty("frameStats")) {
                systemProperty("actinium.frameStats", "true")
            }

            // Send every draw straight to the swapchain, to tell a bad render target from a
            // bad blit back to the screen:
            //   ./gradlew runClient -Prenderer -PignoreFramebuffers
            if (project.hasProperty("ignoreFramebuffers")) {
                systemProperty("actinium.ignoreFramebuffers", "true")
            }

            if (project.hasProperty("debugClear")) {
                systemProperty("actinium.debugClear", "true")
            }

            (project.findProperty("dumpTexture") as String?)?.let {
                systemProperty("actinium.dumpTexture", it)
            }

            if (project.hasProperty("debugTriangle")) {
                systemProperty("actinium.debugTriangle", "true")
            }

            if (project.hasProperty("debugIndexed")) {
                systemProperty("actinium.debugTriangle", "true")
                systemProperty("actinium.debugIndexed", "true")
            }

            // Write what the swapchain actually presented, every sixtieth frame:
            //   ./gradlew runClient -Prenderer -Pcapture=build/frame.png
            // A screenshot taken from the X server proves nothing when the window is not
            // composited; this comes off the image the GPU presented.
            (project.findProperty("capture") as String?)?.let {
                systemProperty("actinium.captureFrame", it)
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

            val connectorCleanPath = project.findProperty("connectorCleanPath") as String?
                ?: file("${gradle.gradleUserHomeDir}/caches/neoformruntime/intermediate_results")
                    .listFiles { f -> f.name.startsWith("rename_") && f.name.endsWith("_output.jar") }
                    ?.maxByOrNull { it.lastModified() }
                    ?.absolutePath
            if (connectorCleanPath != null) {
                systemProperty("connector.clean.path", connectorCleanPath)
            }
        }
        // The packaged jar, loaded the way a player loads it: from the mods folder, with
        // nothing from this source tree on the classpath. It is the only run that exercises
        // the embedded LWJGL libraries, because a dev run loads Actinium from classes and never
        // unpacks a jar-in-jar.
        //
        //   ./gradlew build && cp build/libs/actinium-*.jar run-prod/mods/
        //   ./gradlew runProdClient
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
    compileOnly("org.lwjgl:lwjgl-vulkan:$lwjgl_version")
    compileOnly("org.lwjgl:lwjgl-vma:$lwjgl_version")
    compileOnly("org.lwjgl:lwjgl-shaderc:$lwjgl_version")

    // lwjgl-opengl and LWJGL core ship with Minecraft; referenced for GL constants and the
    // bridge context.
    compileOnly("org.lwjgl:lwjgl-opengl:$lwjgl_version")

    "clientAdditionalRuntimeClasspath"("org.lwjgl:lwjgl-vulkan:$lwjgl_version")
    "clientAdditionalRuntimeClasspath"("org.lwjgl:lwjgl-vma:$lwjgl_version")
    "clientAdditionalRuntimeClasspath"("org.lwjgl:lwjgl-vma:$lwjgl_version:$hostNatives")
    "clientAdditionalRuntimeClasspath"("org.lwjgl:lwjgl-shaderc:$lwjgl_version")
    "clientAdditionalRuntimeClasspath"("org.lwjgl:lwjgl-shaderc:$lwjgl_version:$hostNatives")

    // ---- Bytecode interception --------------------------------------------
    // ASM comes from the NeoForge dev bundle; compileOnly so no second copy is shaded.
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-tree:9.7")
    compileOnly("org.ow2.asm:asm-commons:9.7")
}

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
    description = "Marks each LWJGL Vulkan artifact as an FML game library."
    val sources: FileCollection = files(vulkanLibs)
    val outputDir = layout.buildDirectory.dir("vulkan-libs")
    inputs.files(sources)
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        for (source in sources.files) {
            val destination = File(target, source.name)
            ZipOutputStream(destination.outputStream().buffered()).use { out ->
                var manifest: Manifest? = null
                ZipInputStream(source.inputStream().buffered()).use { input ->
                    var entry = input.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "META-INF/MANIFEST.MF" -> manifest = Manifest(input)
                            entry.name == "META-INF/neoforge.mods.toml" -> {}
                            else -> {
                                out.putNextEntry(ZipEntry(entry.name))
                                input.copyTo(out)
                                out.closeEntry()
                            }
                        }
                        entry = input.nextEntry
                    }
                }
                val finalManifest = manifest ?: Manifest().also {
                    it.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                }
                finalManifest.mainAttributes.putValue("FMLModType", "GAMELIBRARY")
                out.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                finalManifest.write(out)
                out.closeEntry()
            }
        }
    }
}

val jarJarMetadataFile = layout.buildDirectory.file("jarjar-metadata/metadata.json")

val jarJarMetadata by tasks.registering {
    description = "Writes the jar-in-jar manifest FML reads to find the embedded libraries."
    val output = jarJarMetadataFile
    val libraries: FileCollection = files(vulkanLibs)
    val libraryVersion = lwjgl_version
    outputs.file(output)
    doLast {
        val entries = libraries.files.sortedBy { it.name }.map { library ->
            """
            |    {
            |      "identifier": { "group": "org.lwjgl", "artifact": "${library.name.removeSuffix(".jar")}" },
            |      "version": { "range": "[$libraryVersion,)", "artifactVersion": "$libraryVersion" },
            |      "path": "META-INF/jarjar/${library.name}",
            |      "isObfuscated": false
            |    }
            """.trimMargin()
        }
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText("{\n  \"jars\": [\n" + entries.joinToString(",\n") + "\n  ]\n}\n")
    }
}

tasks.jar {
    dependsOn(packVulkanLibs, jarJarMetadata)
    from(layout.buildDirectory.dir("vulkan-libs")) { into("META-INF/jarjar") }
    from(jarJarMetadataFile) { into("META-INF/jarjar") }
    from("LICENSE-VulkanMod-LGPL-3.0.txt")
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

tasks.register<JavaExec>("checkShaders") {
    group = "verification"
    description = "Runs every core shader in the given jars through the GLSL converter and shaderc."
    mainClass = "net.actinium.dev.ShaderConverterCheck"
    classpath = sourceSets.main.get().runtimeClasspath + smokeTestRuntime
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    systemProperty("org.lwjgl.system.stackSize", "1024")
    (project.findProperty("shaderDump") as String?)?.let { systemProperty("actinium.shaderDump", it) }
    args((project.findProperty("shaderJars") as String? ?: "").split(",").filter { it.isNotBlank() })
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
