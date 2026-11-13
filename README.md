<img src="src/main/resources/logo.png" width="96" align="right" alt="Actinium">

# Actinium

Actinium makes Minecraft 1.21.1 render with Vulkan instead of OpenGL. It runs on NeoForge and
needs nothing else installed.

> **Alpha.** Expect bugs. Please report them in [Issues](https://github.com/DragonX1901/Actinium/issues)
> with your `latest.log`.

[Download the latest release](https://github.com/DragonX1901/Actinium/releases)

## Features

- **Vulkan renderer.** The renderer is [VulkanMod](https://github.com/xCollateral/VulkanMod) 0.6.8
  by xCollateral, ported from Fabric to NeoForge and built into this jar.
- **GPU occlusion culling.** Terrain hidden behind hills, walls and caves isn't drawn. On an
  RTX 3050 Ti laptop at render distance 32, about 70% of terrain is skipped and the frame rate
  goes from 67 to about 80 fps.
- **Faster chunk loading.** Full chunk rebuilds take about 20% less time.
- **Smoother chunk loading.** Chunk packets, light updates, rebuild scheduling and uploads are
  spread over several frames instead of landing in one. Flying over new terrain at render
  distance 16 has about 40% fewer frames longer than 4 ms.
- **Leaves culling.** Inner leaf faces hidden behind two or more leaves aren't drawn. In a
  birch forest at render distance 16 this cuts leaf triangles by about 40% and GPU frame time
  by about 20%. Trees look the same; turn it off under Optimizations if you want every face.
- **FPS counter.** Optional on-screen frame rate, simple or with min/max, in any corner. Turn it
  on under Video → Interface.
- **Video settings screen.** Every option explains what it does, and nothing changes until you
  press Apply.
- **Mod compatibility.** Mods written for OpenGL keep working. These include mods that call
  OpenGL directly, mods with their own shaders, and mods whose code conflicts with the renderer.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.248 or newer
- A GPU with Vulkan 1.2 support

Don't install these alongside Actinium (NeoForge refuses to start if they're present):

- VulkanMod or VulkanModNeoForge (already included)
- Sodium, Embeddium
- Iris (shader packs are not supported)

## Tested with

Create, Cobblemon, Sable, Create Aeronautics, ImmediatelyFast, Lithium, FerriteCore,
Xaero's Minimap and World Map (NeoForge), JourneyMap, JEI, Sinytra Connector, Aether, Supplementaries and others.

## Known limitations

- OpenGL-only effects, like Veil's lighting and post effects, don't show.
- Create's moving parts render without Flywheel acceleration, so very large contraptions cost
  more CPU.

## Smoother frames

The remaining hitches while chunks load are Java garbage collection pauses of 3 to 10 ms. Adding
`-XX:+UseZGC -XX:+ZGenerational` to the JVM arguments removes most of them: in the fly test,
frames over 8 ms went from 13 to 2. Worlds load a little slower with it.

## Building

```bash
./gradlew build          # produces build/libs/actinium-<version>.jar
./gradlew smokeTest      # checks pixel conversion, bytecode rewriting, shaders and Vulkan
./gradlew runProdClient  # runs the built jar the way a player does, from run-prod/mods
```

The build embeds the LWJGL Vulkan, VMA and shaderc jars under `META-INF/jarjar`. Gradle 8.8
needs Java 21 to run; point `JAVA_HOME` at a JDK 21 if your default is newer.

## Documentation

- [`docs/RUNTIME-SETUP.md`](docs/RUNTIME-SETUP.md): how the renderer is set up, and the failures
  hit along the way
- [`docs/GL-LAYER.md`](docs/GL-LAYER.md): what the OpenGL compatibility layer covers and what it
  doesn't

## License

Actinium's own code is MIT. See [`LICENSE`](LICENSE).

The renderer under `src/main/java/net/vulkanmod`, ported from VulkanMod by xCollateral, is
LGPL-3.0, and so are the changes made to it here. A few Fabric Rendering API interfaces are
Apache-2.0. See [`NOTICE`](NOTICE) and [`LICENSE-VulkanMod-LGPL-3.0.txt`](LICENSE-VulkanMod-LGPL-3.0.txt).
