import org.gradle.api.tasks.testing.logging.TestLogEvent

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

    // ---- Bytecode interception --------------------------------------------
    // ASM comes from the NeoForge dev bundle; compileOnly so no second copy is shaded.
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-tree:9.7")
    compileOnly("org.ow2.asm:asm-commons:9.7")
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

tasks.register("listConfigs") {
    val names = configurations.names.sorted()
    doLast { names.forEach(::println) }
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
