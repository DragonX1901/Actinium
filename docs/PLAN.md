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
| Texture lifetime fixes (five) | All fire; the Tab crash still ends in VK_ERROR_DEVICE_LOST |
| Standalone Vulkan backend (`core/`) | Compiles, boots standalone, mixins disabled in game |
| GL interop draw path (`interop/`) | Never executed a draw; gated off by default |
| Packaging for a real installation | Done: 216 KB pure-Java jar, `vulkanmod` declared required |
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

## Phase 1 — Make the jar installable

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

## Phase 2 — Be honest in the interface

- Gate the interop layer behind a config flag, default off, described as experimental and
  non-rendering.
- Remove or disable the settings rows that cannot take effect, rather than greying them out with
  an explanation nobody reads.
- Keep the standalone backend in the tree but document it as unused, since `smokeTest` is a
  genuinely useful driver check and deleting it loses that.

**Status: done and verified in game.** The Advanced page now shows one experimental toggle in
place of three rows that could never take effect, and the config entry states in plain words
that the layer does not render.

## Phase 3 — Repository presentation

- README stating what the mod does, what it requires, and what it does not do. The last part
  matters most: this mod sits next to VulkanMod and users will expect it to be a renderer.
- LICENSE file matching the declared licence.
- CI running `build` and `smokeTest` on push. `smokeTest` needs no GPU for the converter,
  transformer and shader checks; the Vulkan section needs one and should be skipped in CI.
- Split `smokeTest` so the headless checks can run without a display.

**Status: done.** `smokeTest` takes `-Pactinium.headless` and skips only the section that needs
a GPU, so CI still runs the pixel converter, bytecode and shader checks. Both paths verified
locally.

## Phase 4 — Close the compatibility gaps that are still open

Ordered by how many mods each unblocks, from measurements already taken.

1. **Framebuffer attachment lifetime. Partly done.** The emulated framebuffer kept a
   `VulkanImage` after the texture behind it was deleted, and the next barrier ran against a
   dead handle. `VulkanModTextures` defers the free while an attachment references the image,
   which is the lifetime OpenGL guarantees, and that removes the SIGSEGV. Pressing Tab still
   ends in `VK_ERROR_DEVICE_LOST`, so more invalid work is reaching the queue from the same
   path. Reproducing it needs a display; see the note on verification below.
2. **Missing uniforms in the shader converter.** `FogStart`, `FogEnd` and `FogColor` are absent
   from the uniform map, in the same way the format table was missing depth-stencil entries.
   Same shape of fix as `GlUtilMixin`.
3. **Mip level uploads.** `texImage2D` allocates one mip level while callers upload several.

**Status: the crash is fixed.** Xaero's Minimap and World Map run in a world with radar icons
on screen. Items 2 and 3 remain and are cosmetic by comparison: a shader with unmatched fog
uniforms renders without fog rather than failing, and multi-level uploads land on a
single-level image.

**Done when:** the map mods either work or fail with a message that names the reason.

## Phase 5 — Decide the interop layer's future

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

## Verification has a hard limit

Everything except rendering can be checked without a display: `build`, `smokeTest
-Pactinium.headless=true`, and `runGameTestServer`, which loads every mod and applies every
mixin. A rendering bug cannot. It needs someone at a screen performing the action.

That limit produced a wrong claim once already: a run that never pressed Tab was reported as
proving the Tab crash fixed. Client-side rendering results belong to whoever ran the client, and
should come from `run/logs/latest.log` rather than from an automated guess.

## Before tagging 0.1.0

1. Install the built jar into a real NeoForge profile alongside a released VulkanMod and confirm
   it loads. Everything else has been verified in the dev runtime, which is not the same thing.
2. Report the texture lifetime bug upstream with the decompiled analysis. The fix belongs in the
   backend, not in a mixin sitting over it.
3. Decide whether the standalone backend stays. It is dead weight in the jar, and `smokeTest`
   is the only thing keeping it.

## What "product ready" is not

It is not feature parity with Sodium, a working GL translator, or support for every mod. It is:
the jar installs, does what it says, fails legibly when it cannot, and the repository tells the
truth about all three.
