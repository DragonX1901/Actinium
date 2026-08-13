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
| Standalone Vulkan backend (`core/`) | Compiles, boots standalone, mixins disabled in game |
| GL interop draw path (`interop/`) | Never executed a draw; known defects |
| Packaging for a real installation | Not done |
| Repository presentation: README, licence, CI | Not done |

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

**Done when:** the jar from `./gradlew build` works in a normal NeoForge profile.

## Phase 2 — Be honest in the interface

- Gate the interop layer behind a config flag, default off, described as experimental and
  non-rendering.
- Remove or disable the settings rows that cannot take effect, rather than greying them out with
  an explanation nobody reads.
- Keep the standalone backend in the tree but document it as unused, since `smokeTest` is a
  genuinely useful driver check and deleting it loses that.

**Done when:** nothing in the UI or config promises behaviour the mod does not deliver.

## Phase 3 — Repository presentation

- README stating what the mod does, what it requires, and what it does not do. The last part
  matters most: this mod sits next to VulkanMod and users will expect it to be a renderer.
- LICENSE file matching the declared licence.
- CI running `build` and `smokeTest` on push. `smokeTest` needs no GPU for the converter,
  transformer and shader checks; the Vulkan section needs one and should be skipped in CI.
- Split `smokeTest` so the headless checks can run without a display.

**Done when:** a stranger can tell in thirty seconds whether this mod is for them, and CI
catches a broken build before a release does.

## Phase 4 — Close the compatibility gaps that are still open

Ordered by how many mods each unblocks, from measurements already taken.

1. **Framebuffer attachment lifetime.** The emulated framebuffer keeps a `VulkanImage` after the
   texture behind it is deleted, and the next barrier runs against a dead handle. This is the
   Xaero's Minimap crash. GL detaches on delete; the emulation does not. Belongs upstream in
   VulkanMod, with the analysis in `RUNTIME-SETUP.md` attached.
2. **Missing uniforms in the shader converter.** `FogStart`, `FogEnd` and `FogColor` are absent
   from the uniform map, in the same way the format table was missing depth-stencil entries.
   Same shape of fix as `GlUtilMixin`.
3. **Mip level uploads.** `texImage2D` allocates one mip level while callers upload several.

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

## What "product ready" is not

It is not feature parity with Sodium, a working GL translator, or support for every mod. It is:
the jar installs, does what it says, fails legibly when it cannot, and the repository tells the
truth about all three.
