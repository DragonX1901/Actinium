# Actinium: plan to a shippable release

## What the product actually is

Actinium began as a from-scratch Vulkan backend. It is not one, and it should stop pretending
to be. NeoForge creates the GLFW window before any mod code runs, VulkanModNeoForge already
solved that, and duplicating a working LGPL project buys nothing.

What exists and works is narrower and more useful:

> **Actinium is a compatibility and configuration layer for Vulkan-rendered Minecraft.** It
> gives VulkanMod a Sodium-style settings screen and fixes the gaps in its OpenGL emulation
> that stop third-party mods from loading.

That product is close to shippable. The from-scratch backend is years from it. Everything below
is sequenced on that reading.

## Where things stand

| Area | State |
|---|---|
| Runs on Vulkan with Create and JEI, in a world | Working, verified |
| Settings screen bound to VulkanMod's 13 renderer settings | Working, verified |
| Compatibility mixins: formats, framebuffer attachments, shader includes, pixel conversion | Working, verified |
| GL context bridge for raw-GL callers | Working, verified |
| Texture and image lifetime fixes | Working; the crash in `vmaDestroyImage` is gone |
| Mixin collisions with the renderer | Working: 19 dropped on a 14-mod profile, no mod named |
| Create: Aeronautics, with sable and Veil | Loads, world loads, player spawns |
| OpenGL context for mods that need one | Working, on by default; the Flywheel conflict is fixed |
| Rendering verified by looking at it | Done on a real GPU, screenshotted |
| Standalone Vulkan backend (`core/`) | Compiles, boots standalone, mixins disabled in game |
| GL interop draw path (`interop/`) | Never executed a draw; gated off by default |
| Packaging for a real installation | Done: the renderer and its LWJGL modules ship inside the jar |
| The built jar loading in a non-dev profile | Done: `runProdClient`, fresh profile, world loaded |
| Repository presentation: README, licence, CI | Done |

## The decision that unblocks everything else

The interop layer is the only part that is both unfinished and shipped. A user installing
Actinium today gets code that cannot run, and a config screen offering an interop mode that
does nothing.

Three options:

1. **Finish it.** Wire the translator to a real draw, fix the resource lifetime gap, build the
   `ILaunchPluginService` host jar. Weeks of work, and the payoff overlaps heavily with what
   VulkanMod's own GL emulation already does.
2. **Cut it.** Delete `interop/`, ship the compatibility layer and the settings screen. Smallest
   honest product, available now.
3. **Gate it.** Keep the code, default it off, mark it experimental, and state plainly in the
   config that it does not render.

**Take option 3 now and decide between 1 and 2 later**, once the compatibility layer has users
and it is clear whether raw-GL mods are a real demand or a hypothetical one. Gating costs a day.
Cutting is irreversible. Finishing is unjustified without evidence anyone needs it.

## Phase 1, Make the jar installable

Nothing else matters until someone other than the author can run this.

- Declare `vulkanmod` as a required dependency in `neoforge.mods.toml`. It is required in fact;
  the mod is inert without it and the settings screen greys out every renderer row.
- Remove the LWJGL Vulkan modules from the shipped artifact. Actinium compiles against them but
  VulkanMod supplies them at runtime, and shipping a second copy is what caused the
  `org.lwjgl.vulkan.video` module resolution failure.
- Set a real licence, version and description. `Unlicense` and `1.0-SNAPSHOT` are MDK defaults,
  not decisions.
- Verify the built jar loads in a non-dev instance, which is the only test that proves any of
  this.

**Status: done.** The jar is 216 KB, contains no LWJGL, ASM or VulkanMod classes, and declares
`vulkanmod` required. What is still untested is the one thing that matters most: loading it in a
real NeoForge profile rather than the dev runtime. That needs a packaged VulkanMod install and
is the first thing to do before tagging a release.

## Phase 2, Be honest in the interface

- Gate the interop layer behind a config flag, default off, described as experimental and
  non-rendering.
- Remove or disable the settings rows that cannot take effect, rather than greying them out with
  an explanation nobody reads.
- Keep the standalone backend in the tree but document it as unused, since `smokeTest` is a
  genuinely useful driver check and deleting it loses that.

**Status: done and verified in game.** The Advanced page now shows one experimental toggle in
place of three rows that could never take effect, and the config entry states in plain words
that the layer does not render.

## Phase 3, Repository presentation

- README stating what the mod does, what it requires, and what it does not do. The last part
  matters most: this mod sits next to VulkanMod and users will expect it to be a renderer.
- LICENSE file matching the declared licence.
- CI running `build` and `smokeTest` on push. `smokeTest` needs no GPU for the converter,
  transformer and shader checks; the Vulkan section needs one and should be skipped in CI.
- Split `smokeTest` so the headless checks can run without a display.

**Status: done.** `smokeTest` takes `-Pactinium.headless` and skips only the section that needs
a GPU, so CI still runs the pixel converter, bytecode and shader checks. Both paths verified
locally.

## Phase 4, Close the compatibility gaps that are still open

Ordered by how many mods each unblocks, from measurements already taken.

1. **Image lifetime. Done, and the cause was not where this plan said it was.** Three separate
   defects were found by measurement, and only the first was the one originally written down:

   - A framebuffer kept a `VulkanImage` after the texture behind it was deleted.
     `VulkanModTextures` defers the free while an attachment references the image, which is the
     lifetime OpenGL guarantees.
   - A barrier could still be recorded against a destroyed image. The guard tested
     `handle == 0`, which misses the case that matters: a handle that reads as ordinary and has
     been destroyed. It now tests a set of live handles.
   - `VulkanImage` has a constructor that wraps an image it does not own, leaving `allocation`
     at zero, and `doFree` cannot tell that from an owned image. It reaches
     `vmaDestroyImage(allocator, image, VK_NULL_HANDLE)` and the process dies in the driver.
     Actinium refuses a free with no allocation.

   Two explanations were tested and rejected on the way: Actinium's own texture code (removing
   both mixins changed nothing) and an image still in use (`vkDeviceWaitIdle` before the first
   free changed nothing).

2. **Mixin collisions with the renderer.** Nineteen mixins across four mods inject into methods
   VulkanMod replaces, and every one is fatal. `MixinPreflight` drops them before the applicator
   runs. Six of them also add an interface, so the mods that own them get `ClassCastException`
   where they relied on it; the drop log names the interface for that reason.

3. **Format table gaps.** Two more, both general: a pixel type the table does not list is fatal
   however ordinary the format, and the snorm and 16-bit families were missing.

4. **Mip level uploads. Open, and narrower than this plan claimed.** The count is not the
   problem: `updateParams` tracks `maxLevel` and `allocateImage` asks for `maxLevel + 1` levels.
   The problem is when it learns the count. A caller uploading levels in order raises `maxLevel`
   on the second call, which sets `needsUpdate`, and the reallocation frees the image holding
   level 0:

   ```java
   void allocateImage(int width, int height, int vkFormat) {
       if (this.vulkanImage != null) { this.vulkanImage.free(); }
       ...setMipLevels(this.maxLevel + 1)...
   }
   ```

   Level 0 is uploaded, then discarded when level 1 arrives. Fixing it means copying the
   existing levels across, or deferring allocation until the caller stops raising `maxLevel`.

   Not attempted, because the symptom is how a texture looks and nothing here can see a texture.
   It needs someone at a screen with a mod that ships mipmapped textures.

The shader converter item that used to sit here has been dropped: `GlslPatcher` builds its
uniform block from whatever the shader declares, so there is no fixed list for `FogStart` and
friends to be missing from. It is also reachable only from the gated interop layer, which does
not render.

**Status: Create: Aeronautics loads, a world loads, and the player spawns in**, with sable and
Veil 4.1.4 present. Xaero's Minimap and World Map run in a world with radar icons on screen.

## Phase 5, Decide the interop layer's future

Revisit options 1 and 2 with evidence. If it is kept, the work is one coherent change, not the
five separate patches the code review found symptoms of:

- Descriptor set layouts, so samplers at `set = 1` and the dynamic UBO at `set = 0` have a
  layout that matches what the shader declares.
- Dynamic offsets passed with the descriptor set, without which every draw after the first reads
  the wrong uniform slice.
- Deferred resource retirement for buffers and images, matching what `TransferQueue` already
  does for staging buffers.
- One agreed binding derivation shared by `PipelineBuilder` and `ActiniumTranslator`.

None of these can be verified until a draw actually runs, which is why they are one change and
not five.

## The decision left for the user, not for the code

`compat.glContextBridge` cannot have a right default. Mods built on Veil die at
`Minecraft.<init>:511` without an OpenGL context; mods built on Flywheel find one, choose an
OpenGL backend, and fail in `GlVertexArrayDSA.bindAttributes`. Both were reproduced on one
profile with nothing changing but whether the context existed.

Offering it only during startup was tried, since the two moments do not overlap, and it aborts
the process: Veil keeps calling OpenGL long past construction.

So it is a switch, off by default, with both consequences stated where someone flipping it will
read them. That was the state of the record for a long time, and it was wrong. There is no
trade: the renderer stubs `GL11.glGetInteger` to return zero, Flywheel sizes its vertex arrays
from a limit, and the bridge only changed whether Flywheel got far enough to read the zero.
`GL11QueryMixin` answers the query, both kinds of mod work with the context present, and the
default is now on.

## Verification has a hard limit

The limit moved. `xvfb-run` gives the client a display without one on anyone's screen, so a full
run, mod loading, world load, spawn, is now checkable headlessly, alongside `build`,
`smokeTest -Pactinium.headless=true` and `runGameTestServer`.

What it does not give is a real GPU driver for OpenGL. Vulkan still reaches the hardware, but
`GL.createCapabilities` lands on llvmpipe, so any claim about how a mod's OpenGL path behaves on
a real driver is unverified. The Flywheel failure above is exactly that kind of claim.

Two limits remain absolute: whether anything looks right on screen, and whether an action a
player performs works.

That limit produced a wrong claim once already: a run that never pressed Tab was reported as
proving the Tab crash fixed. Client-side rendering results belong to whoever ran the client, and
should come from `run/logs/latest.log` rather than from an automated guess.

## Before tagging 0.1.0

1. **Done.** The built jar runs from a mods folder with nothing else installed: it carries
   VulkanModNeoForge 1.0.0 and the LWJGL Vulkan modules as jar-in-jar mod files, turns FML's
   OpenGL loading screen off, and reaches a world. Verified on a profile with no config at all,
   which is what a new install looks like.
2. Done, and the answer was neither of the two on offer. The Flywheel failure was not a driver
   artifact and not a conflict with the bridge: `GL11.glGetInteger` is stubbed to zero by the
   renderer, and Flywheel sizes three arrays from it. Fixed in `GL11QueryMixin`; Create and
   Flywheel now run with the context on.
3. Report the lifetime bugs upstream. Four are worth sending, with the decompiled evidence:
   the framebuffer holding a freed attachment, the barrier recorded against a destroyed image,
   `doFree` calling `vmaDestroyImage` with a null allocation for an image the wrapper never
   owned, and entity rendering writing through an unmapped pointer when JourneyMap is present.
   The first three are understood and have workarounds here. The fourth has a repro and a fault
   address but no traced mechanism, so it should be reported as symptoms rather than a diagnosis;
   it cannot be worked around here because the faulting write is JIT-compiled vanilla code
   through a raw pointer.

   The fourth comes with a repro that needs nobody at a keyboard: add JourneyMap to Create, JEI
   and Fabric API and run under `xvfb-run` with `--quickPlaySingleplayer`.
4. Decide whether the standalone backend stays. It is dead weight in the jar, and `smokeTest`
   is the only thing keeping it.

## What "product ready" is not

It is not feature parity with Sodium, a working GL translator, or support for every mod. It is:
the jar installs, does what it says, fails legibly when it cannot, and the repository tells the
truth about all three.
