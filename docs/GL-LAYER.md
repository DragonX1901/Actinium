# The OpenGL compatibility layer

VulkanMod emulates the OpenGL that Minecraft itself uses. Mods use more, and where the emulation
runs out it throws rather than degrades, so one unimplemented call ends the process. This layer
closes those gaps.

Everything here is derived from the backend's own bytecode. No mod is named in any rule.

## Where a GL call can go

A mod's OpenGL reaches one of three places, and only one of them is emulated:

| Route | Ends up in | Covered by |
|---|---|---|
| `GlStateManager` entry points the renderer replaces (58 of 107) | VulkanMod's `net.vulkanmod.gl.*` | this layer |
| `GlStateManager` entry points it does not (49 of 107) | vanilla, calling `GL11`/`GL20` directly | `GlStateManagerSafetyNet` |
| `org.lwjgl.opengl.GL11`…`GL45` called directly | the driver, needing a real GL context | `compat.glContextBridge` |
| LWJGL entry points the renderer stubs itself | its own `@Overwrite` on `GL11`, `GL14`, `GL15`, `GL30` | `GL11QueryMixin` |

The third route is not emulation at all. A hidden context is created so the calls resolve, and
anything drawn through it goes to a window nobody sees. It is off by default and the reasons are
in [`RUNTIME-SETUP.md`](RUNTIME-SETUP.md).

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

What it still refuses is what has no SPIR-V equivalent: fixed-function built-ins
(`gl_ModelViewMatrix`, `gl_Vertex` and the rest) are rejected at conversion with the name of the
one that was found, and a program that does not have both a vertex and a fragment stage does not
link. A refusal leaves the previously linked program intact and the reason in the program's info
log.

## Checking it

The format tables are pure lookup and are verified headlessly:

```bash
./gradlew smokeTest -Pactinium.headless=true
```

That check exists because a wrong entry otherwise shows up as a mod rendering the wrong colours,
which needs both a screen and the right mod to notice. It round-trips every internal format
through both directions and asserts that `GL_BGRA` does not collapse into `GL_RGBA`.

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

What none of this judges is whether the result looks right. That still needs someone at a screen.
