<img src="src/main/resources/logo.png" width="96" align="right" alt="Actinium">

# Actinium

Vulkan rendering, Sodium-style video settings and OpenGL compatibility fixes for Minecraft
1.21.1 on NeoForge. Install one mod; the renderer comes with it.

## The renderer

Every frame is drawn by **[VulkanModNeoForge](https://github.com/yiyuyan/VulkanModNeoForge)**,
maintained by [yiyuyan](https://github.com/yiyuyan) and ported from
[VulkanMod](https://github.com/xCollateral/VulkanMod) by xCollateral. Actinium ships their
release unmodified inside its jar, configures it, and patches the gaps in its OpenGL emulation.

It is LGPL-3.0 and stays a separate jar, so you can replace it with your own build. See
[`NOTICE`](NOTICE).

## What it does

**A video settings screen worth using.** Tabs across the top, grouped settings on the left, and
a description panel that tells you what each one costs before you change it. Nothing writes
through until you press Apply, so an experiment is one click to undo. Settings are bound to
VulkanMod's renderer config and to vanilla's, in one place.

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

- **It is not a Vulkan renderer of its own.** VulkanModNeoForge does the rendering. Actinium
  ships it, configures it, and patches the gaps in its OpenGL emulation.
- **It does not make every mod work.** Mods that ship their own GLSL or drive raw OpenGL still
  have problems. See [`docs/RUNTIME-SETUP.md`](docs/RUNTIME-SETUP.md) for measured results.
- **The OpenGL interception layer does not render.** It is present, off by default, and marked
  experimental in the config. It allocates buffers and textures on the Vulkan device, but no
  draw call has ever been issued through it.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.248 or newer
- A GPU with Vulkan 1.2

The renderer is bundled. Installing VulkanModNeoForge separately is supported and takes
precedence over the bundled copy when its version is higher.

## Mod compatibility

Tested in a world, not just checked for a clean startup.

| Mod | Result |
|---|---|
| Create 6.0.10 | Works |
| JEI 19.44 | Works |
| Xaero's Minimap 26.4.2 | Loads and draws; crashes when radar icons appear |
| Xaero's World Map 1.44.2 | Loads; raw-GL rendering incomplete |
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

`build` downloads the renderer release, checks it against a pinned SHA-256, wraps the LWJGL
Vulkan artifacts as mod files and embeds all of it under `META-INF/jarjar`. A dev run
(`runClient`) loads Actinium from classes and never unpacks a jar-in-jar, so `runProdClient` is
the only one that exercises what ships.

## Documentation

- [`docs/RUNTIME-SETUP.md`](docs/RUNTIME-SETUP.md), the working configuration, every failure
  hit getting there, and three hypotheses that turned out to be wrong
- [`docs/GL-LAYER.md`](docs/GL-LAYER.md), what the OpenGL layer covers and what it does not
- [`docs/PLAN.md`](docs/PLAN.md), what is left before this is worth calling finished

## Licence

Actinium is MIT. See [`LICENSE`](LICENSE).

The bundled renderer, VulkanModNeoForge by yiyuyan, is LGPL-3.0 and is shipped unmodified in
its own jar. See [`NOTICE`](NOTICE) and
[`LICENSE-VulkanMod-LGPL-3.0.txt`](LICENSE-VulkanMod-LGPL-3.0.txt).
