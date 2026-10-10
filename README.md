<img src="src/main/resources/logo.png" width="96" align="right" alt="Actinium">

# Actinium

Vulkan rendering, Sodium-style video settings and OpenGL compatibility fixes for Minecraft
1.21.1 on NeoForge. Install one mod; the renderer is part of it.

## The renderer

Every frame is drawn by [VulkanMod](https://github.com/xCollateral/VulkanMod) 0.6.7 by
xCollateral, ported from Fabric to NeoForge and compiled into Actinium. It shows up in the mod
list as Actinium and nothing else: no separate renderer mod, no Mixin agent, no library entries.

The port swaps the Fabric pieces for NeoForge ones. Block models go through NeoForge's model data
and per-model render types, fluids through `IClientFluidTypeExtensions`, and chunk layers fire
`RenderLevelStageEvent` like vanilla does. LWJGL and JOML live in NeoForge's boot layer where
Mixin cannot reach, so the OpenGL calls VulkanMod redirects at the LWJGL level are redirected in
`GlStateManager` instead, and projection matrices are converted to Vulkan's depth range in one
place rather than by patching JOML.

The renderer source is in [`src/main/java/net/vulkanmod`](src/main/java/net/vulkanmod) and stays
LGPL-3.0. See [`NOTICE`](NOTICE).

## What it does

**A video settings screen worth using.** Tabs across the top, grouped settings on the left, and
a description panel that tells you what each one costs before you change it. Nothing writes
through until you press Apply, so an experiment is one click to undo. It replaces vanilla's Video
Settings and carries every VulkanMod option next to vanilla's: window mode, fullscreen resolution
and refresh rate, frame limit, VSync, advanced chunk culling, entity and backface culling, the
unique opaque layer, indirect draw, builder threads, frame queue size, texture animations, the
Wayland backend and the GPU selector.

**GPU occlusion culling.** Terrain hidden behind other terrain is not drawn. Each frame the
sections that were visible last frame are drawn first, the GPU builds a depth pyramid from that
depth buffer, tests every remaining section against it in a compute shader, and draws only the
ones that turn out to be visible, in the same frame. Nothing that should be on screen is skipped
or shows up a frame late, which is the usual price of occlusion culling that reads results back
on the CPU. Entities and block entities in hidden sections are skipped as well. Sodium has no
equivalent; it relies on its cave-culling graph, which cannot see that a hill hides the valley
behind it.

Measured on an RTX 3050 Ti laptop GPU, render distance 32, standing on the ground in a birch
forest: 70% of terrain geometry hidden, GPU time for the world pass down from 4.9 ms to 2.6 ms
at 854×480 and from 8.6 ms to 5.6 ms at 1080p, 67 fps to about 80 fps. The CPU side costs
around 0.1 to 0.3 ms a frame. It needs Indirect Draw and a GPU whose graphics queue runs compute
shaders, which is every desktop GPU; the settings screen greys it out otherwise. Toggle it under
Optimizations, and see what it is doing on the F3 screen.

**Faster chunk loading.** Rebuilding every chunk at render distance 32 takes about a fifth less
time than it did (1.22 s to 0.96 s in the same spot), mostly from cheaper biome colour blending,
and the render thread spends about 40% less time scheduling builds while chunks stream in,
because each neighbouring section is copied once per pass instead of once per chunk that needs
it. Details and how it was
measured are in [`docs/RUNTIME-SETUP.md`](docs/RUNTIME-SETUP.md#chunk-building-and-the-render-thread).

**A sturdier GLSL converter for mod shaders.** Mods that ship their own shaders go through
VulkanMod's GLSL-to-Vulkan converter, which rejected common ways of writing GLSL: explicit
`layout(location = N)`, `flat` varyings, interface blocks, uniform arrays and initializers,
licence comments before `#version`, `#ifndef`. Of 17 shaders written in those common styles it
compiled 3; it now compiles all 17, plus every vanilla shader and Xaero's Minimap's. `./gradlew checkShaders`
checks any mod jar. Details in [`docs/RUNTIME-SETUP.md`](docs/RUNTIME-SETUP.md#the-glsl-converter).

**Raw OpenGL textures reach the renderer.** Mods that create textures through LWJGL's OpenGL
bindings instead of `GlStateManager` used to get them in a hidden context, with names that
collided with the renderer's. The hidden context's 2D texture, clear and error entry points now
run against the renderer instead. Xaero's Minimap (NeoForge) draws its map and radar icons with
no validation errors. Details in
[`docs/RUNTIME-SETUP.md`](docs/RUNTIME-SETUP.md#raw-opengl-texture-routing).

**An OpenGL compatibility layer.** VulkanMod emulates the OpenGL that Minecraft itself uses;
mods use more, and where the emulation runs out it throws rather than degrades, so one
unimplemented call ends the process. Actinium closes those gaps, object creation on bind,
buffer targets beyond pixel transfer, format and shader-stage tables, attachments and texture
targets the backend does not implement. See [`docs/GL-LAYER.md`](docs/GL-LAYER.md) for the
coverage matrix and, just as important, what it deliberately does not add.

Some of what that buys, in the form the failure takes without it:

| Fix | Without it |
|---|---|
| Depth-stencil formats | `IllegalStateException: Unexpected format: 36013` when a mod requests a stencil buffer |
| `GL_DEPTH_STENCIL_ATTACHMENT` | `UnsupportedOperationException` binding that buffer to a framebuffer |
| Shader `#moj_import <file>` | Mod shaders never compile; every later frame logs "has no initialized pipeline" |
| Pixel format and row stride | Uploads that are not 4 bytes per pixel render skewed diagonally |
| A real OpenGL context | `No GLCapabilities instance set for the current thread` in any mod calling LWJGL directly |
| OpenGL limit queries | Every limit reads zero, so Flywheel sizes its vertex arrays to nothing and Create's contraptions crash the frame |

No rule names a mod. These are general defects, found through specific mods.

## What it does not do

- **It is not a new renderer.** The rendering is VulkanMod's. Actinium ports it, configures it,
  and patches the gaps in its OpenGL emulation.
- **It does not make every mod work.** Mods that ship their own GLSL or drive raw OpenGL still
  have problems. See [`docs/RUNTIME-SETUP.md`](docs/RUNTIME-SETUP.md) for measured results.
- **The OpenGL interception layer does not render.** It is present, off by default, and marked
  experimental in the config. It allocates buffers and textures on the Vulkan device, but no
  draw call has ever been issued through it.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.248 or newer
- A GPU with Vulkan 1.2

Do not install VulkanMod, VulkanModNeoForge, Sodium, Embeddium or Iris alongside it. NeoForge
refuses to start with any of them present, rather than letting two renderers fight over the
window.

## Mod compatibility

Tested in a world, not just checked for a clean startup. These results were measured against
the VulkanModNeoForge build Actinium used to ship and have not been repeated on the in-tree port
yet.

| Mod | Result |
|---|---|
| Create 6.0.10 | Works |
| JEI 19.44 | Works |
| Xaero's Minimap 26.4.2 | Loads and draws; crashes when radar icons appear |
| Xaero's World Map 1.44.2 | Loads; raw-GL rendering incomplete |
| Xaero's Minimap 26.5.0 (NeoForge), in-tree port | Map, radar icons, Tab overlay, waypoint screen, zoom work; validation clean |
| Xaero's World Map 1.47.0 (NeoForge), in-tree port | Map screen, zoom, reload from cache work; validation clean; short hitch when the map opens |
| Sinytra Connector 2.0.0-beta.17, in-tree port | Loads Fabric mods; Fabric world render events work except `AFTER_SETUP` |
| JourneyMap 6.0.4 | Loads, then crashes the process while entities render |
| Create: Aeronautics (with sable and Veil 4.1.4) | Loads, world loads, player spawns |

Aeronautics needed seven separate fixes, and every one of them is general. Mixins that inject
into methods the renderer replaces are dropped before Mixin tries; an `@Overwrite` narrowed by
another mod's access transformer is widened to match; two gaps in the format table are filled;
and an image wrapper that never allocated its memory is stopped from destroying it. None of
that names a mod, and all of it applies to the next mod with the same shape of problem.

## Building

```bash
./gradlew build          # produces build/libs/actinium-<version>.jar
./gradlew smokeTest      # verifies pixel conversion, bytecode rewriting, shaders and Vulkan
./gradlew runProdClient  # runs the built jar the way a player does, from run-prod/mods
./gradlew scanMods -Pjars=path/to/mod.jar   # reports a mod's OpenGL usage
```

`build` compiles the renderer with the rest of the mod and embeds the LWJGL Vulkan, VMA and
shaderc jars under `META-INF/jarjar`, marked as game libraries so NeoForge loads them without
listing them as mods. A dev run (`runClient`) puts those jars on the classpath directly and never
unpacks a jar-in-jar, so `runProdClient` is the only one that exercises what ships.

Gradle 8.8 needs Java 21 to run; point `JAVA_HOME` at a JDK 21 if your default is newer.

## Documentation

- [`docs/RUNTIME-SETUP.md`](docs/RUNTIME-SETUP.md), the working configuration, every failure
  hit getting there, and three hypotheses that turned out to be wrong
- [`docs/GL-LAYER.md`](docs/GL-LAYER.md), what the OpenGL layer covers and what it does not
- [`docs/PLAN.md`](docs/PLAN.md), what is left before this is worth calling finished

## Licence

Actinium is MIT. See [`LICENSE`](LICENSE).

The renderer under `src/main/java/net/vulkanmod`, ported from VulkanMod by xCollateral, is
LGPL-3.0, and so are the changes made to it here. See [`NOTICE`](NOTICE) and
[`LICENSE-VulkanMod-LGPL-3.0.txt`](LICENSE-VulkanMod-LGPL-3.0.txt).
