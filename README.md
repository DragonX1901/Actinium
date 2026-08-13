# Actinium

Sodium-style video settings and OpenGL compatibility fixes for
[VulkanMod](https://github.com/yiyuyan/VulkanModNeoForge) on Minecraft 1.21.1 (NeoForge).

## What it does

**A video settings screen worth using.** Tabs across the top, grouped settings on the left, and
a description panel that tells you what each one costs before you change it. Nothing writes
through until you press Apply, so an experiment is one click to undo. Settings are bound to
VulkanMod's renderer config and to vanilla's, in one place.

**Compatibility fixes that stop mods crashing.** VulkanMod emulates the parts of OpenGL vanilla
uses. Mods use more, and the gaps are fatal rather than graceful. Actinium fills them:

| Fix | Without it |
|---|---|
| Depth-stencil formats | `IllegalStateException: Unexpected format: 36013` when a mod requests a stencil buffer |
| `GL_DEPTH_STENCIL_ATTACHMENT` | `UnsupportedOperationException` binding that buffer to a framebuffer |
| Shader `#moj_import <file>` | Mod shaders never compile; every later frame logs "has no initialized pipeline" |
| Pixel format and row stride | Uploads that are not 4 bytes per pixel render skewed diagonally |
| A real OpenGL context | `No GLCapabilities instance set for the current thread` in any mod calling LWJGL directly |

None of these name a mod. They are general defects that happen to have been found through
specific ones.

## What it does not do

Worth saying plainly, because Actinium sits next to VulkanMod and looks like a renderer:

- **It is not a Vulkan renderer.** VulkanMod does that. Actinium configures and patches it.
- **It does not make every mod work.** Mods that ship their own GLSL or drive raw OpenGL still
  have problems. See [`docs/RUNTIME-SETUP.md`](docs/RUNTIME-SETUP.md) for measured results.
- **The OpenGL interception layer does not render.** It is present, off by default, and marked
  experimental in the config. It allocates buffers and textures on the Vulkan device, but no
  draw call has ever been issued through it.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.248 or newer
- VulkanModNeoForge 0.5 or newer, plus whatever it requires
- A GPU with Vulkan 1.2

## Mod compatibility

Tested in a world, not just checked for a clean startup.

| Mod | Result |
|---|---|
| Create 6.0.10 | Works |
| JEI 19.44 | Works |
| Xaero's Minimap 26.4.2 | Loads and draws; crashes when radar icons appear |
| Xaero's World Map 1.44.2 | Loads; raw-GL rendering incomplete |
| Create: Aeronautics | Incompatible — bundles Veil, which conflicts with VulkanMod at the mixin level |

## Building

```bash
./gradlew build          # produces build/libs/actinium-<version>.jar
./gradlew smokeTest      # verifies pixel conversion, bytecode rewriting, shaders and Vulkan
./gradlew scanMods -Pjars=path/to/mod.jar   # reports a mod's OpenGL usage
```

The published jar is pure Java. VulkanMod supplies the LWJGL Vulkan modules at runtime, so
Actinium compiles against them and ships none of them; a second copy breaks module resolution.

## Documentation

- [`docs/RUNTIME-SETUP.md`](docs/RUNTIME-SETUP.md) — the working configuration, every failure
  hit getting there, and three hypotheses that turned out to be wrong
- [`docs/PLAN.md`](docs/PLAN.md) — what is left before this is worth calling finished

## Licence

MIT. See [`LICENSE`](LICENSE).

VulkanMod is LGPL-3.0 and is a separate dependency, not bundled or modified here.
