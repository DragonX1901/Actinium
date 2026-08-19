package net.actinium.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ShaderDefines {
    private ShaderDefines() {
    }

    public static Map<String, String> standard(String deviceName) {
        Map<String, String> defines = new LinkedHashMap<>();

        defines.put("MC_VERSION", "12101");
        defines.put("MC_GL_VERSION", "460");
        defines.put("MC_GLSL_VERSION", "460");
        defines.put("MC_MIPMAP_LEVEL", "4");
        defines.put("MC_NORMAL_MAP", "1");
        defines.put("MC_SPECULAR_MAP", "1");
        defines.put("MC_RENDER_QUALITY", "1.0");
        defines.put("MC_SHADOW_QUALITY", "1.0");
        defines.put("MC_HAND_DEPTH", "0.125");
        defines.put("MC_ANISOTROPIC_FILTERING", "0");

        defines.put(operatingSystem(), "1");
        for (Map.Entry<String, String> hardware : hardware(deviceName).entrySet()) {
            defines.put(hardware.getKey(), hardware.getValue());
        }

        defines.put("MC_RENDER_STAGE_NONE", "0");
        defines.put("MC_RENDER_STAGE_SKY", "1");
        defines.put("MC_RENDER_STAGE_SUNSET", "2");
        defines.put("MC_RENDER_STAGE_CUSTOM_SKY", "3");
        defines.put("MC_RENDER_STAGE_SUN", "4");
        defines.put("MC_RENDER_STAGE_MOON", "5");
        defines.put("MC_RENDER_STAGE_STARS", "6");
        defines.put("MC_RENDER_STAGE_VOID", "7");
        defines.put("MC_RENDER_STAGE_TERRAIN_SOLID", "8");
        defines.put("MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED", "9");
        defines.put("MC_RENDER_STAGE_TERRAIN_CUTOUT", "10");
        defines.put("MC_RENDER_STAGE_ENTITIES", "11");
        defines.put("MC_RENDER_STAGE_BLOCK_ENTITIES", "12");
        defines.put("MC_RENDER_STAGE_DESTROY", "13");
        defines.put("MC_RENDER_STAGE_OUTLINE", "14");
        defines.put("MC_RENDER_STAGE_DEBUG", "15");
        defines.put("MC_RENDER_STAGE_HAND_SOLID", "16");
        defines.put("MC_RENDER_STAGE_TERRAIN_TRANSLUCENT", "17");
        defines.put("MC_RENDER_STAGE_TRIPWIRE", "18");
        defines.put("MC_RENDER_STAGE_PARTICLES", "19");
        defines.put("MC_RENDER_STAGE_CLOUDS", "20");
        defines.put("MC_RENDER_STAGE_RAIN_SNOW", "21");
        defines.put("MC_RENDER_STAGE_WORLD_BORDER", "22");
        defines.put("MC_RENDER_STAGE_HAND_TRANSLUCENT", "23");

        defines.put("IS_IRIS", "1");
        defines.put("IRIS_TAG_SUPPORT", "2");
        defines.put("IRIS_HAS_TRANSLUCENCY_SORTING", "1");
        defines.put("IRIS_VERSION", "1080012");

        return defines;
    }

    private static String operatingSystem() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "MC_OS_WINDOWS";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "MC_OS_MAC";
        }
        if (name.contains("linux") || name.contains("unix")) {
            return "MC_OS_LINUX";
        }
        return "MC_OS_UNKNOWN";
    }

    private static Map<String, String> hardware(String deviceName) {
        String device = deviceName == null ? "" : deviceName.toLowerCase(Locale.ROOT);
        Map<String, String> defines = new LinkedHashMap<>();

        String vendor = "MC_GL_VENDOR_OTHER";
        String renderer = "MC_GL_RENDERER_OTHER";

        if (device.contains("nvidia") || device.contains("geforce") || device.contains("quadro")) {
            vendor = "MC_GL_VENDOR_NVIDIA";
            renderer = device.contains("quadro") ? "MC_GL_RENDERER_QUADRO" : "MC_GL_RENDERER_GEFORCE";
        } else if (device.contains("amd") || device.contains("radeon")) {
            vendor = "MC_GL_VENDOR_AMD";
            renderer = "MC_GL_RENDERER_RADEON";
        } else if (device.contains("intel")) {
            vendor = "MC_GL_VENDOR_INTEL";
            renderer = "MC_GL_RENDERER_INTEL";
        } else if (device.contains("llvmpipe") || device.contains("mesa") || device.contains("lavapipe")) {
            vendor = "MC_GL_VENDOR_XORG";
            renderer = device.contains("llvmpipe") ? "MC_GL_RENDERER_GALLIUM" : "MC_GL_RENDERER_MESA";
        }

        defines.put(vendor, "1");
        defines.put(renderer, "1");
        return defines;
    }
}
