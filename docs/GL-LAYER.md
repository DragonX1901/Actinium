# The OpenGL compatibility layer

VulkanMod emulates the OpenGL that Minecraft itself uses. Mods use more, and where the emulation
runs out it throws rather than degrades, so one unimplemented call ends the process. This layer
closes those gaps.

Everything here is derived from the backend's own bytecode. No mod is named in any rule.

## Where a GL call can go

A mod's OpenGL reaches one of three places, and only one of them is emulated:

| Route | Ends up in | Covered by |
|---|---|---|
| `GlStateManager` entry points the renderer replaces | VulkanMod's `net.vulkanmod.gl.*` | this layer |
| `GlStateManager` entry points it does not | vanilla, calling `GL11`/`GL20` directly | `GlStateManagerSafetyNet` |
| `org.lwjgl.opengl.GL11`…`GL45` called directly | the driver, needing a real GL context | `compat.glContextBridge` |

On Fabric VulkanMod also stubs a fourth route, `@Overwrite`s on LWJGL's `GL11`, `GL14`, `GL15`
and `GL30`. On NeoForge LWJGL sits in the boot layer where Mixin cannot reach, so those never
apply; the calls vanilla made through them are redirected in `GlStateManager` instead, and
everything else takes the third route.

The third route is not emulation at all. A hidden context is created so the calls resolve, and
anything drawn through it goes to a window nobody sees. It is on by default and can be turned off
in the settings screen; the reasons are in [`RUNTIME-SETUP.md`](RUNTIME-SETUP.md).

The second is the one that is easy to miss, because the game never takes it: the renderer
replaced every vanilla caller that would have. A mod that compiles a shader through
`GlStateManager.glCreateShader`, or binds a vertex array, reaches untouched vanilla code and
therefore real OpenGL, which under Vulkan is not there:

```
IllegalStateException: No GLCapabilities instance set for the current thread
```

The safety net makes those calls no-ops when no context exists, and leaves them alone when the
bridge has provided one. Queries answer the way OpenGL answers about an object that was never
created, `glGetProgrami` gives 0, `_glGetUniformLocation` gives -1, which matters because 0 is
a valid uniform location and a caller would go on to use it.

Of the 49, **45 reach OpenGL and all 45 are covered.** The other four need nothing:
`_backupGlState` reads GlStateManager's own tracked fields, and the three
`setup*DiffuseLighting` methods do matrix arithmetic and hand the result to
`RenderSystem.setShaderLights`. No GL call in any of them.

Covering all 45 rather than a selection was deliberate. An earlier pass took the likely ones -
shaders, uniforms, vertex arrays, stencil, and left fixed-function leftovers and pixel readback
on the grounds that no Vulkan-era mod would call them. That is the same guess that let this
whole route go unnoticed, and it is wrong the same way: a mod setting a `mat4` uniform fails
exactly like one setting an `int`, so covering half is covering none for whoever calls the
other half.

## What the backend refuses, and what happens now

The backend has 40 throw sites across six classes. Grouped by what a mod was trying to do:

| Category | Was | Now |
|---|---|---|
| Binding a name that was never generated (textures, buffers, framebuffers, renderbuffers, programs) | `NullPointerException` | the object is created, as OpenGL does |
| Deleting a framebuffer that does not exist | `NullPointerException` | ignored, as OpenGL does |
| Buffer targets other than pixel pack/unpack | `IllegalStateException` | bound and served from CPU storage |
| Pixel type outside a four-entry list | `IllegalStateException` | the format answers on its own |
| Internal formats outside a nine-entry list | `IllegalStateException` | snorm, 16-bit and float families added |
| Reverse format lookup outside a four-entry list | `IllegalStateException` | answers for everything the forward table maps |
| Shader extensions other than `.vsh` and `.fsh` | `RuntimeException` | `.vert`, `.frag`, `.geom`, `.comp` and friends |
| Texture parameters on 3D, cube and array targets | `UnsupportedOperationException` | ignored; reads answer zero |
| Colour attachments past the first | `UnsupportedOperationException` | ignored, with a warning naming the loss |
| Attaching a cube face or a mip level | `UnsupportedOperationException` | attached as the base 2D image |
| Depth-stencil and stencil attachments | `UnsupportedOperationException` | mapped onto the depth attachment |
| Renderbuffer sampler state and mipmaps | `UnsupportedOperationException` | ignored; neither exists on a renderbuffer |
| Out-of-range filters and mip levels | `IllegalArgumentException` | ignored, as OpenGL does |
| Every OpenGL limit query | `return 0`, silently | the driver's value, or the specification minimum |
| `glGenerateMipmap` | validates its argument and returns | builds the chain from level 0 |
| `glGetTexImage(level)` | reads level 0 whatever level was asked for | reads the level asked for |
| Uploads with no `GL_UNPACK_ROW_LENGTH` | copy zero bytes, silently | the width, as OpenGL defines |
| A texture that gains a mip level | the image holding level 0 is freed | the uploaded levels are copied across |

The limit-query row cost the most to find. The renderer mixes into LWJGL and stubs
`GL11.glGetInteger` to return zero for every limit, so a mod sizing an array from
`GL_MAX_VERTEX_ATTRIBS` allocates nothing and then fails somewhere that names neither OpenGL nor
the renderer. It read for a long time as a conflict between two mods;
[`RUNTIME-SETUP.md`](RUNTIME-SETUP.md) records the three wrong explanations tested before the
right one.

## The principle

OpenGL almost never throws. An invalid enum sets a flag that most callers never read, and the
call does nothing. Code written against that contract treats a rejected call as free.

So the rule throughout is: **do what OpenGL does, and where that is impossible, do nothing
loudly.** A mod that loses a feature keeps running and says so in the log. A mod that hits an
exception takes every other mod down with it.

That trade is stated per case rather than assumed. Dropping a second colour attachment means a
deferred renderer draws to one target and its output is wrong, better than the alternative, and
not good, so it warns.

## What this does not add

- **Multiple render targets.** The emulated framebuffer holds one colour image.
- **3D textures, cube maps, texture arrays.** Parameter calls are tolerated; no such image is
  ever created, so a mod needing one still will not render.
- **Drawing from emulated buffers.** A buffer bound to `GL_ARRAY_BUFFER` can be allocated,
  written, mapped and read. It is never handed to a draw call.
- **Compute, transform feedback, occlusion queries, sync objects.** Not present in the backend
  and not added here.

Each of those needs real Vulkan resources behind it, not a translation of the call.

## Coverage

All 40 throw sites are accounted for. They are not spread evenly, because several methods throw
the same way more than once:

| Class | Sites |
|---|---|
| `VkGlTexture` | 10 |
| `VkGlFramebuffer` | 9 |
| `GlUtil` | 8 |
| `VkGlRenderbuffer` | 7 |
| `VkGlBuffer` | 5 |
| `VkGlProgram` | 1 |

One of them, `checkTarget`, is now unreachable rather than handled: its only caller is a method
this layer takes over.

Five bugs turned up on the way that are not refusals at all, and so would never have shown up by
chasing exceptions. Every one of them is silent:

- `glBufferData(target, ByteBuffer, usage)` stores nothing. A pixel buffer filled through it
  reads back as whatever it held before.
- The same method assigns the *unpack* binding whatever the target was, so an upload through
  `GL_PIXEL_PACK_BUFFER` rebinds the other one.
- An upload sizes its staging copy from `GL_UNPACK_ROW_LENGTH` without the rule that zero means
  the transfer width, so a mod that never touches pixel store state uploads nothing and gets a
  texture full of another texture's bytes.
- `glGenerateMipmap` validates its argument and returns. Levels above 0 keep whatever was in
  that memory.
- Declaring a mip level a texture does not have yet frees the image holding level 0.

The last three were found by writing a known pattern into level 0, declaring level 1, and
reading level 0 back. It came back as `00FFFFFF`.

## Shaders in the interception layer

The interception layer (`actinium.interopMode=TRANSLATE`) owns the GL shader objects itself, so
a mod that builds a program the ordinary way gets Vulkan objects instead of a driver that is not
there:

| Call | What it does here |
|---|---|
| `glCreateShader`, `glShaderSource`, `glCompileShader` | keeps the source, resolves `#include` and `#moj_import`, and records whether the stage is one this layer can convert |
| `glAttachShader`, `glBindAttribLocation`, `glLinkProgram` | converts the attached stages together and creates the shader modules, descriptor set layout and pipeline layout |
| `glGetShaderi`, `glGetProgrami`, `glGetShaderInfoLog`, `glGetProgramInfoLog` | answer the way OpenGL does, so a mod that checks `GL_LINK_STATUS` reads a real answer and gets the reason in its log |
| `glGetUniformLocation`, `glGetAttribLocation` | the byte offset into the uniform block, and the attribute location the conversion settled on |
| `glMatrixMode`, `glLoadMatrixf`, `glPushMatrix`, `glTranslatef`, `glOrtho` and the rest | drive the matrix stacks that feed a legacy shader's fixed-function matrices |
| `glFogf`, `glFogi`, `glFogfv` | hold the fog state those shaders read through `gl_Fog` |
| `glVertexPointer`, `glColorPointer`, `glNormalPointer`, `glTexCoordPointer`, `glEnableClientState` | bind to the attribute slots the conversion pins for `gl_Vertex` and friends |
| `glUniform*` | writes into the program's staging block, uploaded through the per-frame uniform ring at draw time |
| `glUseProgram`, `glDeleteProgram` | bind and destroy, waiting for the device to go idle before anything a frame may still be reading is freed |

Conversion is a program-wide pass rather than a per-stage one, because the two are not
independent:

- **One uniform block.** Loose `uniform` declarations from every stage are collected into a
  single `std140` block at set 0, binding 0. Patching each stage on its own would put the vertex
  stage's first uniform and the fragment stage's first uniform at the same offset, and one of
  them would read the other's bytes.
- **One set of locations.** A varying gets its location from its name, so `out vec2 texCoord0`
  in the vertex stage and `in vec2 texCoord0` in the fragment stage agree even when the two
  files declare their varyings in a different order.
- **Samplers after the block.** Each sampler gets its own binding, starting at 1, and
  `glUniform1i` on a sampler picks the texture unit its descriptor reads from, rather than
  writing four bytes into the uniform block.
- **Bound attributes win.** A location set with `glBindAttribLocation` before the link is kept,
  and generated locations are placed around it, `mat4` inputs taking the four they occupy.

### Shaders written before GLSL 150

Mod shaders are often older than the pipeline they now run on, so the converter modernises before
it patches. Nothing here is optional: a `#version 120` shader has no SPIR-V equivalent until every
one of these is gone.

| Legacy | Becomes |
|---|---|
| `attribute`, `varying` | `in`, and `out` or `in` depending on the stage |
| `gl_ModelViewMatrix`, `gl_ProjectionMatrix`, `gl_ModelViewProjectionMatrix`, their inverses and transposes, `gl_NormalMatrix` | block members the layer fills from its own matrix stacks |
| `gl_TextureMatrix[i]` | an eight-element `mat4` array, filled per texture unit |
| `gl_Vertex`, `gl_Color`, `gl_Normal`, `gl_MultiTexCoord0`…`7` | inputs pinned to locations 0, 1, 2 and 3+n, the slots `glVertexPointer` and friends feed |
| `gl_FrontColor`, `gl_BackColor`, `gl_Color` in a fragment shader | one varying carrying the interpolated colour |
| `gl_TexCoord[i]` | an eight-element `vec4` varying |
| `gl_FragData[n]`, `gl_FragColor` | declared outputs, as many as the shader writes |
| `gl_Fog.color`, `.start`, `.end`, `.density`, `.scale` | block members filled from `glFog*` |
| `ftransform()` | the model-view-projection product |
| `texture2D`, `texture2DLod`, `texture2DProj`, `shadow2D` and their 1D/3D/Cube relatives | `texture`, `textureLod`, `textureProj` |
| `gl_VertexID`, `gl_InstanceID` | `gl_VertexIndex`, `gl_InstanceIndex`, which is what Vulkan GLSL calls them |

The matrices are not just declared, they are fed. `glMatrixMode`, `glLoadIdentity`,
`glLoadMatrixf`, `glMultMatrixf`, `glPushMatrix`, `glPopMatrix`, `glTranslatef`, `glScalef`,
`glRotatef`, `glOrtho` and `glFrustum` drive a matrix stack per mode, `glFog*` holds the fog
state, and every draw writes whichever of those a program actually declared into its uniform
block. A program that never mentions them pays nothing.

`glVertexPointer`, `glColorPointer`, `glNormalPointer` and `glTexCoordPointer`, with
`glEnableClientState`, bind to the same locations the conversion pinned, so a shader that reads
`gl_Vertex` gets the buffer that `glVertexPointer` named.

Two things are still refused, with the reason in the program's info log. Fixed-function lighting,
material and clip-plane state (`gl_LightSource`, `gl_FrontMaterial`, `gl_ClipPlane` and the rest)
has no equivalent and no source of truth here. Storage images (`image2D` and friends) need
`glBindImageTexture`, which this layer does not implement, so a shader that declares one is
rejected rather than given a descriptor nothing writes to.

## Checking it

The format tables are pure lookup and are verified headlessly:

```bash
./gradlew smokeTest -Pactinium.headless=true
```

That check exists because a wrong entry otherwise shows up as a mod rendering the wrong colours,
which needs both a screen and the right mod to notice. It round-trips every internal format
through both directions and asserts that `GL_BGRA` does not collapse into `GL_RGBA`.

Every GLSL program shipped in the game's own jars is converted on each run of that command, which
is the check that matters most: 58 programs, vertex and fragment, through include resolution,
modernisation, patching and shaderc, asserting zero failures. It is what caught duplicate
`#version` lines from `#moj_import`, `flat` varyings losing their location, and `gl_VertexID`.

Shader packs are a different matter. Eight of the most downloaded ones, 927 programs between them,
are the corpus: 822 convert, and another 33 fail on symbols the pack itself never defines, like
Complementary's clrwl_computeFragment, which is called in four files and declared in none. Those
cannot compile under any loader, so the corpus counts them separately. The 72 that remain are the
layer's to answer for: storage images whose memory qualifiers do not survive the rewrite, swizzles
past the end of a vector, and code behind pack options that only exist once shaders.properties is
read. Even at 822, converting a pack's GLSL is not the same as running the pack; Iris defines the
options, resolves the conditional includes and drives the gbuffers/deferred/composite pipeline. Iris and
Oculus are opted out of the redirect in the default config, so no pack reaches this layer in a
running game. Converting a pack's GLSL is not the same as running the pack, and this does not run
it.

The shader conversion is checked there too, and for the same reason. `GL program conversion`
converts a vanilla-style vertex and fragment pair together and asserts the parts that only go
wrong once something is on screen: one shared block, a varying on the same location in both
stages, a bound attribute location that survived, and SPIR-V out of both stages. `GL shader
objects` drives the same program through the GL entry points a mod would call, and on a machine
with no Vulkan device it asserts the failure is the reported kind, `GL_LINK_STATUS` false with a
reason in the info log, rather than an exception out of a mod's shader loader.

The rest needs the calls to actually happen, and no mod on hand makes most of them. A client run
that reaches a world therefore proves only that nothing regressed, a mixin that silently failed
to apply looks exactly like one that was never needed. So the layer tests itself:

```bash
./gradlew runClient -PquickPlay="<world>" -PglSelfTest
```

It makes twelve calls that the backend refuses, with the arguments that make it refuse, and
reports how many got through.

```
OpenGL layer self-test passed: 12 calls that the backend refuses now succeed
```

A test that always passes proves nothing, so it was checked against its own absence. With only
the buffer mixin removed and everything else left in place:

```
OpenGL layer self-test: 4 of 12 checks failed
  bind an array buffer:     NullPointerException: bound texture is null
  allocate an array buffer: IllegalStateException: Unexpected value: 34962
  map an array buffer:      IllegalStateException: Unexpected value: 34962
  bind a uniform buffer:    NullPointerException: bound texture is null
```

Exactly the four that mixin is responsible for, and no others. Removing the whole layer does not
even get that far: the profile dies during startup with
`IllegalStateException: Unexpected format: 36013`, before the first tick.

## What the renderer draws today

Renderer mode draws Minecraft's menus. The title screen comes off the swapchain
the right way up, logo, panorama and buttons, on a CPU device with no GPU
present, captured with `-Pcapture=<file>`.

Two things stood between a black window and that frame, and both were one line:

- **Every triangle was culled.** The viewport is flipped, so clip space keeps
  the winding it had in OpenGL, and the front face was being inverted on top of
  that. A triangle drawn through the same entry points with culling disabled
  came out fine, which is what isolated it.
- **The flip has to happen once.** Flipping the render target and the swapchain
  both cancels out; flipping only the pass that reaches the screen leaves the
  target in the orientation the blit expects. The winding then has to follow the
  same rule, and it does, per pipeline, because the pipeline key already carries
  which target it is for.

What it does not draw yet is text. The glyphs reach the layer, `-PdumpTexture`
shows the font atlas holding two of them, and the text shader discards anything
with no alpha to sample, so the labels are absent rather than wrong. A run of the
menus is otherwise clean: the validation layer reports nothing across a boot to
the title screen.

Two things about the font atlas are known and neither explains it on its own.
`glTexImage2D` is called once per mipmap level and each call used to resize the
image to that level, which left later uploads outside the recorded bounds; that
is fixed. Uploads above level zero are still dropped, since the images carry one
level. The glyphs that do arrive land, and the atlas still holds two of them.

## Testing without a GPU

Everything above checks the layer without drawing anything. The one question that matters most,
does a draw through this layer put the right pixels on the target, needs a Vulkan device, and CI
machines do not have one.

They do not need one. Mesa's lavapipe is a full Vulkan 1.3 implementation that runs on the CPU,
so the whole path can be exercised on a machine with no GPU, no display and no driver:

```bash
apt-get install -y mesa-vulkan-drivers vulkan-tools vulkan-validationlayers xvfb
scripts/vm-test.sh
```

The script finds the toolchain, points `VK_ICD_FILENAMES` at lavapipe when there is no `/dev/dri`,
and runs the headless checks, the shipped shader corpus and the offscreen draw. On a real GPU it
leaves the driver alone and runs the same three.

```
gpu: none, rendering on lavapipe (/usr/share/vulkan/icd.d/lvp_icd.json)
PASS  headless checks
PASS  shipped shader corpus
PASS  offscreen draw
      centre pixel: 64, 128, 191, 255
```

`./gradlew renderTest` is the draw. It brings the device up without a surface, builds a 64x64
colour target, then goes through the GL entry points a mod would call: compile and link a program,
fill a vertex buffer, upload a texture, set a `vec4` uniform and a sampler unit, and
`glDrawArrays`. It reads the image back and checks two pixels. The centre must be the uniform
colour times the white texture, `64, 128, 191, 255`, which no part of the pipeline produces by
accident: a broken uniform block gives black, a broken sampler binding gives black or garbage, a
broken vertex layout gives the clear colour. The corner must still be the clear colour, which
catches a triangle that covered everything.

Validation runs with it. `VK_LAYER_KHRONOS_validation`, when installed, is enabled by default and
its output goes through the same logger, so a wrong descriptor type or a missing barrier fails the
run rather than being some driver's silent tolerance. It is what caught the layer leaking its
descriptor pool, uniform ring, quad index buffer, samplers, transfer pool and every GL object it
had created at device teardown; `ActiniumTranslator.shutdown()` now frees them, and the run is
clean.

`scripts/vm-test.sh --client` goes further and boots the game itself under `xvfb-run` on the same
software device, with `-Prenderer` so Actinium's own Vulkan renderer owns the window. That is
slow, and it proves something different: that the mod loads, the renderer initialises and the
first frames go through, on a machine with no graphics hardware at all. The layer-over-VulkanMod
path is a different run, `prodClient` with the built jar in `run-prod/mods`; a plain dev
`runClient` with an empty `run/mods` has no renderer at all and dies compiling vanilla's
`blit_screen`, which is the run being wrong rather than the code.

On lavapipe that boot reaches:

```
Actinium/Window: Suppressing OpenGL context; initialising Vulkan (validation=true)
Actinium/VkContext: Vulkan device: llvmpipe (LLVM 20.1.2, 256 bits)
Actinium/Swapchain: Swapchain 854x480, 4 images, vsync=true
Actinium: Actinium online — TRANSLATE on llvmpipe
```

with the resource packs loaded and the texture atlases built, and no validation error anywhere in
the run. The script then grabs the virtual display into `build/vm-shot.png`. That frame is black,
and it is honest that it is: in renderer mode Actinium owns the swapchain and presents it, but
vanilla's own drawing goes through `GlStateManager`, which this layer does not render. The
screenshot is there to make that visible rather than assumed, and to fail loudly on the day the
first frame stops being empty by accident.

What none of this judges is whether the result looks right. That still needs someone at a screen.
