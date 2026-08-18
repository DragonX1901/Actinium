# Actinium runtime setup

The Vulkan backend is [VulkanModNeoForge](https://github.com/yiyuyan/VulkanModNeoForge)
(LGPL-3.0) by yiyuyan, which owns the window, the device and the render paths. Actinium ships
it, configures it, and patches the gaps in its OpenGL emulation.

Installing Actinium is enough. The jar carries the renderer and the LWJGL Vulkan bindings it
needs, both as ordinary mod files under `META-INF/jarjar`, and FML unpacks them at startup.

This file records what a working stack looks like and every failure hit getting there.

## Installing

Drop `actinium-<version>.jar` into `mods`. Nothing else is required.

A `vulkanmod` jar installed separately takes precedence when its version is higher, which is
how LGPL-3.0's replacement requirement is met. Two things follow from that:

- The compatibility fixes name the classes they patch and check for each one, so a renderer
  release that renames or drops a class loses only the fix that touched it.
- Every fix declares `require = 0`. A version whose shape has moved on loses that patch instead
  of taking the game down.

## The first-launch crash, and why Actinium fixes it for you

FML draws its loading screen with OpenGL on the game's own window. A Vulkan backend
re-initialises GLFW while mods are still loading, which destroys the context that screen is
drawing into, and the next frame ends the process from native code with no crash report:

```
FATAL ERROR in native method: No context is current
  at org.lwjgl.opengl.GL30C.glBindFramebuffer
  at net.neoforged.fml.earlydisplay.EarlyFramebuffer.activate
  at net.neoforged.fml.loading.ImmediateWindowHandler.renderTick
```

The usual advice is to set `earlyWindowControl = false` in `config/fml.toml` by hand. Actinium
writes that setting itself, and because the window already exists by the time any mod code runs,
it also swaps in FML's own no-op window provider for the launch under way. Both are needed: the
setting alone fixes the second launch, the swap alone forgets by the next one.

## Verified working

Minecraft 1.21.1, NeoForge 21.1.248, NVIDIA RTX 3050 Ti, Mesa 25.2.8, Wayland.

```
[VulkanMod] Vulkan device: NVIDIA GeForce RTX 3050 Ti Laptop GPU (discrete)
[VulkanMod] drawIndirectCount: supported
[minecraft] Sound engine started
[minecraft] Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas
[minecraft] Stopping!
```

World creation, chunk saving and a clean shutdown all work.

## `run/mods` contents

| Jar | Size | Why |
|---|---|---|
| `vulkanmod-nofabric.jar` | 19 MB | The Vulkan backend, locally patched (see below) |
| `fabric-renderer-api-v1-3.4.1+9125b6dc19.jar` | 60 KB | The only Fabric API VulkanMod actually uses |
| `fabric-block-view-api-v2-1.0.11+e9036fd419.jar` | 26 KB | Dependency of the renderer API |
| `fabric-api-base-0.4.42+d1308ded19.jar` | 35 KB | Dependency of the above |

**121 KB of Fabric modules, not the 5.6 MB Forgified Fabric API.**

### Why the full Forgified Fabric API is not needed

VulkanMod's classes reference exactly one Fabric package, 201 times:

```
net/fabricmc/fabric/api/renderer/v1
```

Nothing else. The three modules above are extracted from FFAPI's jar-in-jar
(`META-INF/jars/`) and dropped into `mods/` directly.

`fabric-renderer-indigo` must **not** be included. It is the reference implementation of the
renderer API and declares a `net.fabricmc.api.ClientModInitializer` entrypoint, which drags in
the Fabric loader and crashes mod loading with `NoClassDefFoundError`. VulkanMod provides its
own `VulkanModRenderer`, so Indigo is redundant.

The 1.21.3 `NeoFabricRenderApis` shim from the VulkanMod repo's `libs/` does not work on
1.21.1: its mixins target `com.mojang.blaze3d.resource.GraphicsResourceAllocator`, a class that
does not exist in this version.

### The local patch to VulkanMod

`META-INF/neoforge.mods.toml` in the published jar declares a hard dependency on
`fabric_api_base`. That requirement is inherited metadata from the Fabric original. The three
modules above satisfy it anyway, so the patch is optional now, but the dependency block was
removed while diagnosing which Fabric code was genuinely reachable.

Upstream would be the right place to fix this. Do not redistribute the patched jar.

### First launch asks for a restart

VulkanMod's transformation service writes an early-window setting and exits:

```
[VulkanModDiscover] The earlyWindowControl has been turned. Please restart the game.
```

Run `./gradlew runClient` again. This happens once.

## Build configuration

Nothing is added to `additionalRuntimeClasspath`. VulkanMod extracts `lwjgl-vulkan`,
`lwjgl-vma` and `lwjgl-shaderc` from its own jar into `run/vulkan-libs/` and registers them as
NeoForge mod files (`vulkan_lwjgl`, `vma`, `vulkan_shaderc`). Adding the same artifacts through
Gradle produces:

```
ResolutionException: Modules vulkan_lwjgl and org.lwjgl.vulkan
                     export package org.lwjgl.vulkan.video
```

Actinium compiles against the LWJGL Vulkan bindings (`compileOnly`) and resolves them at
runtime through VulkanMod's modules.

## Actinium's own backend

`actinium.mixins.json` has an empty `client` list. `WindowMixin` and `RenderSystemMixin` are
still in the source tree but are not applied: they fight VulkanMod's mixins over the same
call sites, and VulkanMod's version is the one that works.

`ActiniumMixinPlugin` still runs. It raises LWJGL's memory stack from 64 KB to 1 MB before
`MemoryStack` loads, which matters on any machine with more than one GPU.

Actinium logs this on startup, which is expected and correct:

```
[Actinium] Vulkan was never initialised, the Window mixin did not apply.
```

### Why the standalone backend was abandoned

NeoForge does not create the GLFW window inside `Window.<init>`. It calls
`ImmediateWindowHandler.setupMinecraftWindow(...)`, which returns the window FML's early
loading screen already created, with an OpenGL context attached. No hint set inside that
constructor can change the window's client API, so `glfwCreateWindowSurface` fails with:

```
GLFW error 65540: Vulkan: Window surface creation requires the window
                  to have the client API set to GLFW_NO_API
```

VulkanMod solves it by installing its own `ImmediateWindowProvider`, reflectively replacing the
private static `provider` field in `ImmediateWindowHandler` from an `IModFileCandidateLocator`
service that runs before FML builds its window.

## The settings screen

Actinium's Sodium-style screen replaces the Video Settings button. Verified in game:

```
[Actinium/Screens] Replacing net.vulkanmod.config.gui.VOptionScreen
                   with Actinium's video settings
```

Two screens had to be intercepted, not one. VulkanMod mixes into `OptionsScreen`'s
video-settings button lambda and returns its own `VOptionScreen`, so vanilla's
`VideoSettingsScreen` is never constructed and matching only on that type never fires.
`ActiniumClientEvents` matches VulkanMod's screen by class name, which keeps VulkanMod an
optional integration rather than a compile-time dependency.

### Where each row writes

Three storages feed the screen.

| Storage | Owns | Rows |
|---|---|---|
| Vanilla `Options` | Settings Minecraft has always had | Render distance, simulation distance, framerate limit, vsync, view bobbing, clouds, mipmaps, entity shadows |
| VulkanMod config | The renderer | GPU culling, Hi-Z occlusion, indirect drawing, frustum culling, entity culling, back-face culling, chunk build threads, frame queue size, unified opaque layer, ambient occlusion |
| `ActiniumOptions` | The interop layer | Interop mode, validation layers, shader debug info |

VulkanMod's config is reached reflectively through `net.actinium.compat.VulkanModConfig`, by
field name rather than by type. Actinium compiles and runs with VulkanMod absent; a direct
reference would make it a hard dependency. Verified at startup:

```
[Actinium/VulkanMod] VulkanMod config bound: 13 setting(s) available
[Actinium] Renderer backend: VulkanMod. Settings screen is bound to its config.
```

A row whose field is missing greys out and names the field, so a VulkanMod version that renames
something degrades to a disabled row instead of a crash or a switch that does nothing.

### Side effects on apply

Several of VulkanMod's own option handlers do more than assign a field. Assigning alone leaves
the renderer out of sync, so Apply reproduces the same work:

| Setting | Extra work |
|---|---|
| `builderThreads` | Resizes the chunk build thread pool |
| `frameQueueSize` | Schedules a swapchain rebuild |
| `uniqueOpaqueLayer` | Rebuilds the terrain render type mapping |
| vsync | Schedules a swapchain rebuild |

Chunk reloads go through `OptionFlag.REQUIRES_RENDERER_RELOAD` instead, so changing eight
settings triggers one rebuild. The screen snapshots VulkanMod's values when it opens, so
closing it without touching the thread count does not restart the build pool.

### Theme

Amber accent on a warm near-black ground. One accent colour carries the active tab, the
modified-row bar and keyboard focus. Warnings use a redder orange (`#FF8A4C`) rather than a
second yellow, so "this rebuilds every chunk" does not read as decoration.

## The interop layer

`net.actinium.backend.VulkanBackend` abstracts the Vulkan objects the GL translation layer
needs, so it no longer talks to Actinium's own `VkContext`. Two implementations:
`VulkanModBackend` (reflective, what actually runs) and `ActiniumBackend` (used by
`./gradlew smokeTest`).

Actinium's buffers and images are allocated from **VulkanMod's own VMA allocator**, not a
second one. Two allocators on one device compete for the same heaps and each keeps its own
free lists, which wastes VRAM and hides fragmentation from both.

### What VulkanMod exposes, and what it does not

| Needed | Source |
|---|---|
| `VkDevice` | `Vulkan.getVkDevice()` |
| VMA allocator | `Vulkan.getAllocator()` |
| `VkPhysicalDevice` | `DeviceManager.physicalDevice` |
| Graphics queue | `DeviceManager.getGraphicsQueue().queue()` |
| Graphics family index | `Queue.getQueueFamilies().graphicsFamily` |
| Frame index | `Renderer.getCurrentFrame()` |
| Frame command buffer | `Renderer.currentCmdBuffer`, **private field** |
| `VkRenderPass` handle | `RenderPass.id`, **package-private field** |

The last two have no public accessor and are read by reflection, once, read-only. If a
VulkanMod update renames either, `VulkanModBackend.tryCreate()` returns null and the interop
layer stays inert rather than crashing a frame.

### Frame state is resolved per draw

VulkanMod cycles through several command buffers. Caching one goes stale the moment the frame
advances, so the translator reads it per draw. When the frame index changes, the descriptor
pool and uniform ring are reset wholesale, VulkanMod has already waited on that frame's fence,
so everything from the last time the index came round has finished executing.

A draw with no render pass bound is skipped with a warning rather than recorded. Binding a
graphics pipeline outside a render pass is invalid and crashes the driver.

### Verified in game

`InteropSelfTest` runs once on the first client tick after the device exists. It goes through
the public `ActiniumGL` entry points, exactly as a redirected mod call would:

```
[Actinium/Backend]  VulkanMod device bound for interop: allocator=0x77ee39826000, graphics family 0
[Actinium/Backend]  Interop layer bound to VulkanMod
[Actinium/SelfTest] device=0x77ef5f227210 allocator=0x77ee39826000 graphicsFamily=0
[Actinium/SelfTest]   buffer: VkBuffer=0x77ed524c3920 allocation=0x77ee398782c0 size=256 mapped=true
[Actinium/SelfTest]   texture: VkImage=0x77ed52590440 view=0x77ed525836c0 4x4 layout=5
[Actinium/SelfTest] Interop self-test PASSED
```

Layout 5 is `VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL`, which means the staging copy, both
layout transitions and the queue submit all completed on VulkanMod's graphics queue.

The self-test deliberately does not draw. A draw needs a compiled program and a pipeline
layout, and neither exists until a mod supplies GLSL. The resource path is proven; the draw
path is not.

## Third-party OpenGL mods

Tested with **Create 6.0.10** (plus Flywheel and Ponder, which it bundles). Two crashes, both
inside VulkanMod's GL emulation, both fixed by Actinium compatibility mixins.

### Crash 1: missing depth-stencil format

```
java.lang.IllegalStateException: Unexpected format: 36013
    at net.vulkanmod.gl.GlUtil.vulkanFormat(GlUtil.java:94)
    at com.mojang.blaze3d.pipeline.RenderTarget.createBuffers(RenderTarget.java:110)
    at net.createmod.catnip.platform.NeoForgeClientHooksHelper.enableStencilBuffer
```

36013 is `0x8CAD`, `GL_DEPTH32F_STENCIL8`. Ponder calls `RenderTarget.enableStencil()`;
VulkanMod maps depth-only formats and throws on combined ones. `GlUtilMixin` supplies the
missing entries, and `GLFormats` asks the device which combined depth-stencil format it
actually supports rather than hardcoding one, the spec guarantees `D24_UNORM_S8_UINT` **or**
`D32_SFLOAT_S8_UINT`, not both.

### Crash 2: missing attachment point

```
java.lang.UnsupportedOperationException
    at net.vulkanmod.gl.VkGlFramebuffer.framebufferTexture2D(VkGlFramebuffer.java:105)
```

That method accepts `GL_COLOR_ATTACHMENT0` and `GL_DEPTH_ATTACHMENT` only. Vanilla's stencil
path passes `GL_DEPTH_STENCIL_ATTACHMENT` (`0x821A`). `VkGlFramebufferMixin` rewrites the
argument: a combined depth-stencil image *is* the depth attachment, and Vulkan does not split
the two the way GL does.

### A trap worth remembering

The mixin plugin originally guarded these with `Class.forName(target, false, loader)`. That
still **defines** the class, and a mixin target defined before Mixin sees it can never be
transformed, the format mixin silently stopped applying and the original crash came back with
no error anywhere. `classExists` now probes for the `.class` resource and never loads it.

### Result

Create loads, and the world renders on Vulkan. Verified in game.

### The Fabric dependency, revisited

The three-module set gets to the main menu. It does **not** get into a world: VulkanMod's chunk
builder needs `fabric-rendering-fluids-v1`:

```
NoClassDefFoundError: net/fabricmc/fabric/api/client/render/fluid/v1/FluidRendering$DefaultRenderer
    at net.vulkanmod.render.chunk.build.thread.BuilderResources.<init>
```

That module declares a Fabric entrypoint, so it needs the Forgified Fabric Loader, and dropping
the loader jar into `mods/` by itself does not register it. So:

| Setup | Result |
|---|---|
| 3 Fabric modules, 121 KB | Main menu, settings screen, no world |
| Full Forgified Fabric API, 5.6 MB | World loads, Create runs |

Avoiding the umbrella entirely would mean patching VulkanMod's fluid-rendering integration out,
which is upstream work rather than something to bolt on from outside.

## Mod compatibility, measured

Every mod below was loaded into a world and looked at, not just checked for a clean startup.

| Mod | Version | Result | Failure |
|---|---|---|---|
| Create | 6.0.10 | **Works** | needed two Actinium fixes (above) |
| JEI | 19.44.0.401 | **Works** | item panel, 48 pages, tooltips, search all render |
| Xaero's Minimap | 26.4.2 | Fails | own shader will not compile |
| Xaero's World Map | 1.44.2 | Fails | calls raw LWJGL GL |
| JourneyMap | 6.0.4 | Fails | own shader has no pipeline |

### The pattern

Mods that render through vanilla work. Mods that ship their own GLSL or call
`org.lwjgl.opengl.*` directly do not. Two distinct failure modes, two distinct fixes needed.

**Custom shaders.** Xaero's Minimap ships `xaerolib/framebuffer_lines`, which references the
vanilla fog uniforms:

```
[VulkanMod] Error: field FogStart not present in uniform map
[VulkanMod] Error: field FogEnd not present in uniform map
[VulkanMod] Error: field FogColor not present in uniform map
[VulkanMod] Error on shader xaerolib/framebuffer_lines conversion/compilation
java.lang.NullPointerException
```

JourneyMap fails at the next stage, once per frame, forever:

```
NullPointerException: Shader journeymap:jm_position_tex_color has no initialized pipeline
```

This is the same shape as the depth-stencil format gap: VulkanMod's uniform table covers what
vanilla uses. Actinium already has the GLSL translation half (`GlslPatcher`, `ShaderCompiler`,
verified by `./gradlew smokeTest`); what is missing is wiring it into VulkanMod's shader
pipeline creation, plus filling the uniform map the way `GlUtilMixin` fills the format table.

**Raw OpenGL.** Xaero's World Map does not go through `GlStateManager`:

```
Xaero's World Map has crashed!
Caused by: IllegalStateException: No GLCapabilities instance set for the current thread
```

There is no GL context on a `GLFW_NO_API` window, so LWJGL has no capabilities to hand out.
This is exactly what `GLRedirectTransformer` exists to fix, and it cannot run until the
`ILaunchPluginService` host jar exists. Until then, raw-GL mods have nowhere for their calls
to land.

### What that means for the coverage numbers

The earlier jar scan said Create was 19% covered by the Java translator. That number measured
static call sites. In practice Create works anyway, because it routes through Flywheel and
`GlStateManager`, which VulkanMod already handles. Xaero's World Map is the opposite case: a
smaller GL footprint, but raw, and it fails completely. Call-site counts predict effort, not
outcomes.

## Generic fix: shader include resolution

`SPIRVUtilsMixin` replaces VulkanMod's include preprocessing with `ShaderIncludes`. No mod is
named anywhere in it.

The original:

```java
String included = line.replace("#include", "").replace(" ", "")
                      .replace("\"", "").replace("\r", "").replace("\n", "");
IOUtils.toString(Objects.requireNonNull(
        SPIRVUtils.class.getResourceAsStream("/assets/vulkanmod/shaders/include/" + included)));
```

Two generic defects. It strips double quotes but not angle brackets, so vanilla's own
`#moj_import <fog.glsl>` becomes a lookup for the literal `<fog.glsl>` and misses a file the
backend actually ships. And it only searches the backend's jar, so a mod's own include can
never be found. The `requireNonNull` then throws an NPE that the surrounding
`catch (IOException)` does not catch: shader creation aborts, the pipeline is left null, and
every later frame logs "has no initialized pipeline".

The replacement accepts both directive spellings and resolves in order: the backend's bundled
includes, then `shaders/include/<name>` through Minecraft's resource manager across every
namespace, then `shaders/core/<name>`. An unresolved include is left in place and logged once,
so shaderc reports undefined symbols with line numbers instead of an NPE several frames away.

### Two bugs in the fix itself, both classloader-related

The first version probed mixin targets with `Class.forName(name, false, loader)`. That
*defines* the class, and a mixin target defined before Mixin sees it can never be transformed,
so the guard silently disabled the mixin it was guarding. `classExists` now looks for the
`.class` resource and never loads it.

The second version called `ShaderIncludes.class.getResourceAsStream(...)`. Mod jars get
separate classloaders, so Actinium cannot see VulkanMod's resources: every lookup missed,
including the `fog.glsl` the backend ships. Bundled includes are now read through
`net.vulkanmod.vulkan.shader.SPIRVUtils`'s own class.

### Result

| Mod | Before | After |
|---|---|---|
| Xaero's Minimap | crash during shader compilation | loads, runs, minimap frame/compass/coordinates draw; **map texture renders corrupted** |
| Xaero's World Map | crash | unchanged: raw LWJGL GL, needs the transformer |

## Generic fix: a real OpenGL context for raw GL callers

`GLContextBridge` creates one hidden 1x1 GLFW window with a real GL context and makes it
current on the render thread. Nothing is drawn to it. Its job is to exist, so that
`GL.createCapabilities()` has something to bind and every `org.lwjgl.opengl` entry point
resolves to a driver function instead of throwing.

The game window is `GLFW_NO_API` so Vulkan can own its surface, which is correct and cannot
change: one window cannot serve both a GL context and a Vulkan swapchain. The side effect is
that mods calling LWJGL directly die on their first call:

```
IllegalStateException: No GLCapabilities instance set for the current thread
    at org.lwjgl.opengl.GL.getCapabilities(GL.java:226)
    at xaero.map.MapLimiter.determineDriverType(MapLimiter.java:33)
```

A GL context and a Vulkan device coexist fine on one thread; they are separate thread state and
never interact. The backend never calls GL, so nothing it does is affected.

```
[Actinium/GLBridge] OpenGL bridge context ready: Intel Mesa Intel(R) Iris(R) Xe Graphics
                    (4.6 (Core Profile) Mesa 25.2.8)
```

**Limit worth stating plainly.** Anything a mod draws into the default framebuffer lands in the
hidden window and is never seen. Resource calls, queries, uploads and framebuffer work all
execute correctly. Presenting GL-rendered pixels through the Vulkan swapchain needs image
sharing via `VK_KHR_external_memory`, which is separate work. This turns "crashes on the first
GL call" into "runs, and anything drawn straight to the screen is invisible", strictly better,
and a complete fix for the large class of mods that only probe or manage resources.

With Mesa, launching with `MESA_LOADER_DRIVER_OVERRIDE=zink` makes that context itself run on
Vulkan, so raw GL work executes on the same GPU rather than a second driver stack.

## Generic fix: no nested render passes

In OpenGL, `glBindFramebuffer` is legal at any moment. In Vulkan a render pass is a scope, and
beginning one inside another is invalid; without validation layers the driver walks into
undefined behaviour. Mods bind their own framebuffers mid-frame constantly.

Xaero's Minimap pre-renders each entity's radar icon into an offscreen target while the main
pass is recording. The result was not an exception but a SIGSEGV:

```
C  [libnvidia-glcore.so]
J  net.vulkanmod.vulkan.texture.VulkanImage.transitionImageLayout
J  net.vulkanmod.vulkan.framebuffer.Framebuffer.beginRenderPass
J  net.vulkanmod.gl.VkGlFramebuffer.beginRendering
J  net.vulkanmod.gl.VkGlFramebuffer.bindFramebuffer
j  xaero.common.graphics.ImprovedFramebuffer.bindAsMainTarget
j  xaero.hud.minimap.radar.icon.creator.RadarIconCreator.getFinalIcon
```

`VkGlFramebufferMixin` now ends the active pass before a bind starts another, through
`VulkanModRenderPasses`. Ending and restarting a pass is not free, on a tiler it flushes the
tile buffer, but correctness comes first, and a mod that binds targets per object pays the
same kind of cost under OpenGL.

### Result

| Mod | Before | After |
|---|---|---|
| Xaero's Minimap | crash in shader compilation, then SIGSEGV on radar icons | **runs in world**; frame, compass, coordinates and radar icons all draw. Map texture still renders corrupted. |
| Xaero's World Map | crash on `GL.getCapabilities()` | **runs in world**, no crash |

Verified together in a world: 0 crashes, 0 `No GLCapabilities` errors, 0 Xaero crash reports.

## Generic fix: pixel format conversion

`GLPixelConverter` + `VkGlTextureMixin` replace VulkanMod's client-memory upload reader.

Its own source marks the defect:

```java
private static ByteBuffer getByteBuffer(int width, int height, long pixels) {
    // TODO: hardcoded format size
    int formatSize = 4;
```

Four bytes per pixel, always. `GL_RGB` is three, `GL_RED` one, `GL_RG` two, `GL_RGBA16F`
eight, packed shorts two. A wrong stride offsets every row a little further than the last,
which is the diagonal skew the minimap draws. `GL_UNPACK_ALIGNMENT` is not tracked at all,
though it defaults to 4 and pads every row whose length is not a multiple of it.

The converter computes bytes-per-pixel from `(format, type)`, honours `GL_UNPACK_ROW_LENGTH`,
`SKIP_ROWS`, `SKIP_PIXELS` and `ALIGNMENT`, and emits tightly packed RGBA8. It handles the
packed short types, `BGRA`/`BGR` swizzles, legacy luminance, and normalises float and wide
integer components. One reusable scratch buffer, since uploads happen per frame.

Verified in `./gradlew smokeTest`, which needs no GPU:

```
RGB8 width 3: stride=12 (9 bytes padded to 12)
RGB8 width 2: stride=8  (6 bytes padded to 8)
RGB8 -> RGBA8 with alignment padding: correct
BGRA -> RGBA8: swizzled correctly
```

Only `texSubImage2D` reads client memory through that helper; `texImage2D` does not, and a
redirect on a call site that does not exist fails the injection check outright, which is how
that was found.

## Not fixed: the offscreen framebuffer crash

Pressing Tab with Xaero's Minimap installed loses the Vulkan device. What follows is what has
been measured, including the part where several of these fixes were making things worse.

### Forcing validation layers in from outside

The backend hardcodes `ENABLE_VALIDATION_LAYERS = false`, but the loader can inject them without
touching its code:

```bash
VK_LOADER_LAYERS_ENABLE=VK_LAYER_KHRONOS_validation \
VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation \
./gradlew runClient -PquickPlay="New World"
```

This changed the investigation completely. Five previous attempts inferred what was invalid from
crash stacks; the layer simply says it.

### What validation reported

```
UNASSIGNED-CoreValidation-DrawState-InvalidImageLayout (ERROR)
vkQueueSubmit(): command buffer expects VkImage 0xb730000000b73
  (aspectMask 0x1, array layer 0, mip level 0) to be in
  VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
  -- instead, current layout is VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
```

The image is **alive**. This is a layout mismatch on a colour image, not the released handle that
five earlier fixes were written against.

### Measured A/B, all at the same point (world load, before any input)

| Configuration | InvalidImageLayout | DEVICE_LOST |
|---|---|---|
| No Actinium mixins | 0 | 0 |
| All except `VkGlFramebufferMixin` | 0 | 0 |
| Plus the depth-stencil remap only | 16 | 1 |
| Plus the remap and the render-pass guards | 29 | 1 |

Two conclusions follow, and both correct earlier claims made in this file.

**The render-pass guards were pure harm.** Ending the active pass before every framebuffer bind,
and refusing to begin one outside a frame, added 13 layout errors. Both were written against
hypotheses this document had already recorded as disproved: `Renderer.beginRendering` ends the
previous pass itself and guards on `recordingCmds`. Ending a pass out of band desynchronises the
backend's layout tracking, because `RenderPass.endRenderPass` is what records an attachment's
final layout. They were described here as turning a crash into a cleaner failure. They were
producing one. They are removed.

**The depth-stencil remap is necessary and unsound.** Without it, Create's Ponder library cannot
start:

```
UnsupportedOperationException at VkGlFramebuffer.framebufferTexture2D(VkGlFramebuffer.java:105)
  at net.createmod.catnip.platform.NeoForgeClientHooksHelper.enableStencilBuffer
```

With it, 16 layout errors appear that are not there otherwise. Presenting a combined
depth-stencil image as a plain depth attachment changes how the render pass is built, and colour
attachments end up sampled while still in `TRANSFER_DST_OPTIMAL`.

### State of the goal

The minimap does not work. Tab still loses the device. What is different is that the failure is
now attributable: it is not a released image, and roughly half of what was previously called a
fix was generating errors of its own.

The remaining question is narrow and testable: why does attaching a depth-stencil image as the
depth attachment leave a colour image in `TRANSFER_DST_OPTIMAL` at submit time. That is one
validation message away from an answer, and it is the next thing to run down.

## Generic fix: mixins that inject into methods the renderer replaces

Create: Aeronautics bundles **Veil**, a rendering framework that patches the same vanilla
internals VulkanMod does. The first symptom looked like a conflict with no principled fix:

```
InvalidInjectionException: @At("INVOKE") on AbstractTexture::setFilterDSA with priority 1000
cannot inject into AbstractTexture::setFilter(ZZ)V merged by
net.vulkanmod.mixin.texture.MAbstractTexture with priority 1000
```

It is a general one. A Vulkan renderer replaces rendering methods rather than adding to them,
and Mixin refuses to let anything else inject into a method another mixin has merged. On this
profile 256 methods are replaced and 19 mixins across four mods try to inject into them.

`MixinPreflight` reads every registered mixin's bytecode, pairs the replaced methods against the
injected ones, and removes the mixins that cannot apply. It names no mod: the rule comes from
bytecode present at runtime.

Two things about it were learned the hard way and are easy to get wrong again.

**Catching the error afterwards is worse than the crash.** Mixin wraps a target class's whole
applicator run in one try block, so an injector failing part-way abandons every mixin already
merged into that class. Registering an `IMixinErrorHandler` that returns `ErrorAction.WARN`
let the game continue with a class that no longer verifies:

```
VerifyError: Instruction type does not match stack map
LevelRenderer.handler$bmm000$sable$compileSections @53
  stack map locals[0] = dev/ryanhcode/sable/.../LevelRendererMixin
  current frame  locals[0] = net/minecraft/client/renderer/LevelRenderer
```

Once a mixin starts applying there is no safe way to abandon it.

**Three details of Mixin's internals decide whether this works at all**, each of which produced
a run that silently found nothing:

- `@Mixin` has CLASS retention, so it is in `RuntimeInvisibleAnnotations`. Reading only
  `visibleAnnotations` finds no targets and looks exactly like a profile with no collisions.
- `Mixins.getConfigs()` is emptied as configs are selected. By the time the applicator runs it
  returns nothing; `Config.allConfigs`, a static map, is the durable view.
- Removing a mixin from a config's list is not enough. Mixin dispatches through `mixinMapping`,
  a target-to-mixin index, and a mixin still listed there still applies.

Timing is `acceptTargets`, which Mixin calls once every config is prepared and before the first
class is transformed. `onLoad` is far too early: measured on this profile, only 102 mixins from
two libraries were registered by then, and no renderer config among them.

### The rule is deliberately broader than Mixin's

Mixin refuses an injection into a merged method only when the injection point needs the original
instructions. `HEAD`, `RETURN` and `TAIL` need nothing and are accepted. Relaxing the check to
match that exactly was measured: drops fell from 19 to 9 on this profile and Mixin raised no
refusal, which proves the narrower model is the accurate one.

It is still the wrong rule to ship. Being accepted is not the same as working. A structural
injection into a replaced body runs against code that no longer does what its author assumed,
and the nine survivors left Veil half-patched:

```
NullPointerException: Cannot invoke DynamicBufferProgramAccessor.getId()
because "this.vertexProgram" is null
  at ShaderInstance.handler$zfp000$veil$init
```

A second attempt tried to keep both: drop the accepted ones only for a mod that was already
losing something to a hard collision, so a mod is either left alone or disabled coherently. On a
Create-only profile that worked exactly as intended, taking the drops from three to none. On the
Veil profile it did not, keyed on the config or on `getCleanSourceId`, because Veil spreads its
mixins over fourteen configs and neither groups them into one unit. Same NPE.

So anything whose target is replaced at equal or higher priority is dropped, structural or not.
The cost is real, known and measured: on a profile with Create and no Veil, three mixins go that
Mixin would have accepted, Flywheel's GL state tracking and one Fabric API world-render hook.
That is the price of the profiles with Veil starting at all.

Worth retrying only with a way to group a mod's configs that actually works. Two have been
tried and neither does.

### Priority decides how it fails, not whether

Sable's mixin declares `priority = 1002`, above VulkanMod's 1000, so Mixin permits the
injection and it fails later instead:

```
InjectionError: Callback method sable$cull failed injection check, (0/1) succeeded
```

Its anchor was `@At("INVOKE_STRING")` on a call inside the vanilla body, which the replacement
no longer contains. So the check covers both: a merge at equal or higher priority, or an
injection point naming an instruction rather than `HEAD`, `RETURN` or `TAIL`.

## The OpenGL bridge conflict that was not a conflict

For most of this project's life the record here said that two kinds of mod wanted opposite
things: Veil-based mods die without an OpenGL context, Flywheel-based mods break when one
exists, and no default could satisfy both.

That was wrong, and it was wrong in a way worth keeping on the record, because the evidence for
it was real every time. Turning the bridge on did break Flywheel:

```
ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
  at flywheel.backend.gl.array.GlVertexArrayDSA.bindAttributes
```

The cause is one method. The renderer mixes into LWJGL itself and stubs a query:

```java
@Mixin({GL11.class})
public class GL11M {
    @Overwrite(remap = false)
    public static int glGetInteger(int pname) {
        return 0;
    }
```

Every OpenGL limit reads zero. Flywheel sizes three arrays from `GL_MAX_VERTEX_ATTRIBS`:

```java
protected static final int MAX_ATTRIBS = GL32.glGetInteger(34921);
private final VertexAttribute[] attributes = new VertexAttribute[MAX_ATTRIBS];
```

At zero they are empty and the first attribute bind fails.

The bridge never caused it. Without a context Flywheel catches the missing capabilities and
disables its backends, so the zero is never reached; with one it proceeds and hits it. Switching
the bridge off hid the defect instead of avoiding it, which is exactly why it kept looking like
a conflict between two mods.

### Finding it took ruling out three plausible stories

Each was tested and each was wrong:

| Hypothesis | Test | Result |
|---|---|---|
| Actinium's mixin preflight drops Flywheel's GL mixins | ran with `-PnoPreflight` | crash unchanged |
| The context is unbound from the render thread | rebound it every frame and counted | rebinds: 0 |
| Two LWJGL copies, so `GL11` uses a different memory stack | compared classloaders and stack sizes | identical, same class |

What located it was one measurement: the same query, on the same thread, microseconds apart.

```
MAX_VERTEX_ATTRIBS via stack=0, via own buffer=16
```

`nglGetIntegerv` reaches the driver and answers correctly. Only the wrapper is stubbed. A
standalone process creating the identical context reported 16 and 16384, which proved the
context was never the problem.

### The fix

`GL11QueryMixin` replaces the stub: the driver's value when a context exists, and the
specification's required minimum when it does not. A minimum is a floor every implementation
must meet, so a caller sizing an array by one gets something conservative and usable, which zero
never is.

Two constraints shape how it is written, both from the module system. An `@Inject` is
impossible, because LWJGL does not read Mixin's module and the callback parameter is
unreachable:

```
IllegalAccessError: class org.lwjgl.opengl.GL11 (in module org.lwjgl.opengl) cannot access
class org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
```

And the overwrite body cannot name a class in this mod either, so the call out is reflective.
That is the same shape the renderer uses in its own LWJGL overwrites, for the same reason.

With it, Create and Flywheel run with the bridge on, Flywheel loads its shaders and starts its
worker threads instead of throwing, and Veil-based mods keep the context they need. There is no
trade to make.

## Dropping a mixin can cost an interface

`MixinPreflight` drops Veil's `DynamicBufferShaderInstanceMixin`, which was also adding an
interface to its target:

```
ClassCastException: class net.minecraft.client.renderer.ShaderInstance cannot be cast to
class foundry.veil.ext.ShaderInstanceExtension
```

Fifty-eight of those, and the game still reaches a world with Veil's dynamic buffers dead. It is
the documented cost of dropping at mixin granularity, appearing far from the drop and looking
like an unrelated bug, so the drop log now names the interface it removes.

## Generic fix: destroying an image the wrapper never allocated

With the mixin collisions resolved, Create: Aeronautics reached the first frame and the process
died in the driver:

```
SIGSEGV in libnvidia-glcore.so
  Vma.nvmaDestroyImage / MemoryManager.freeImage / VulkanImage.doFree
  MemoryManager.freeImages / initFrame / Renderer.beginFrame
```

Three explanations were tested and rejected before the real one:

- **Actinium's own texture lifetime code.** Removing `VkGlTextureMixin` and `VulkanImageMixin`
  left the crash unchanged.
- **A double free.** `doFree` guards on `id != 0`, and a live-handle set showed the handle being
  destroyed was one nothing had destroyed before.
- **An image still in use.** A `vkDeviceWaitIdle` before the first batch of deferred frees
  changed nothing.

The cause is one constructor:

```java
public VulkanImage(String name, long id, int format, int mipLevels, int width, int height,
                   int formatSize, int usage, long imageView) {
    this.id = id;
    this.mainImageView = imageView;
    ...
}
```

It wraps an image that already exists, so it sets the handle and leaves `allocation` at zero.
`doFree` does not distinguish that from an owned image and calls
`vmaDestroyImage(allocator, image, VK_NULL_HANDLE)`. VMA reads the allocation to find the memory
block and dereferences null.

Under validation the same run survives, because a software driver tolerates it, and instead
reports the borrowed handle as invalid once its real owner destroys it. That is why the crash
looked device-specific and is not.

Actinium refuses the free when a wrapper holds a handle but no allocation. Skipping leaks that
wrapper's image view, which is bounded, against destroying another object's image.

With this, Aeronautics loads, a world loads, and the player spawns in.

## Not fixed: entity rendering writes through a stale pointer

Adding JourneyMap to an otherwise working profile kills the process while entities render:

```
SIGSEGV, si_code 1 (SEGV_MAPERR), si_addr 0x76d45ee00390
J  ModelPart.renderCubes
J  ModelPart.render / HierarchicalModel.renderToBuffer / EyesLayer.render
J  LivingEntityRenderer.render / EntityRenderDispatcher.render
J  LevelRenderer.handler$zgl000$vulkanmod$renderEntities
```

`MAPERR` means the address is not mapped at all, and the same value sits in R8 and R10: a write
through a pointer to memory that no longer exists.

**Established:** the address is unmapped, the crash is inside entity model rendering, and it
needs JourneyMap. The same profile without it stays up.

**Not established:** which write produces the bad pointer. VulkanMod's `BufferBuilderM` holds a
raw one and refreshes it in some methods but not others:

```java
public void vertex(float x, float y, float z, ...) {
    this.ptr = this.beginVertex();      // refreshed, then written
    MemoryUtil.memPutFloat(this.ptr + 0L, x);
}

public void position(float x, float y, float z) {
    MemoryUtil.memPutFloat(this.ptr + 0L, x);   // uses whatever ptr holds
}
```

`beginVertex` is `@Shadow`, so the pointer comes from vanilla's growable `ByteBufferBuilder` and
stops being valid when that buffer grows, the same shape as the image bug fixed above, one
level down. That is a plausible mechanism and no more: `position` carries no annotation, so
whether `ModelPart.renderCubes` reaches it has not been traced, and if it did so generally then
entities would break without JourneyMap, which they do not. Pinning it down means tracing the
consumer that JourneyMap installs.

Not fixed here. The faulting write is JIT-compiled vanilla code going through a raw `long`, so
checking it means a branch on every vertex of every entity. This belongs upstream, and the
report should carry the repro rather than the guess above.

**Reproducing it takes no display interaction:** add JourneyMap to Create, JEI and Fabric API,
then `xvfb-run ./gradlew runClient -PquickPlay="<world>"`. It dies within seconds of spawning.
Without JourneyMap the same profile stays up. That is the first repro of this crash family that
needs nobody at a keyboard, and it is worth keeping.

## Generic fix: access transformers versus private overwrites

An access transformer widening a vanilla method breaks any `@Overwrite` of it that declares
itself private:

```
PRIVATE @Overwrite method renderEntity in vulkanmod.mixins.json:render.entity.LevelRendererM
cannot reduce visibiliy of PUBLIC target method
```

Simulated, which ships inside sable and so inside Create: Aeronautics, widens it:

```
public-f net.minecraft.client.renderer.LevelRenderer renderEntity(...)V
```

Neither side is wrong. Actinium widens the overwrite to match, which produces exactly the
public method the other transformer asked for. Narrowing the vanilla method instead would leave
every caller compiled against the widened version throwing `IllegalAccessError`.

Worth knowing: **NeoForge access transformers do reach mixin classes**, not just Minecraft's.
That is what makes the fix possible.

## Still to do
- `./gradlew smokeTest` still exercises Actinium's own `VkContext`, which no longer runs in
  game. It remains useful as a driver check.
