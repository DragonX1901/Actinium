package net.actinium.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class ShaderUniforms {
    private static final Map<String, String> UNIFORMS = new LinkedHashMap<>();

    private static final Map<String, String> ATTRIBUTES = new LinkedHashMap<>();

    private static final Map<String, String> SAMPLERS = new LinkedHashMap<>();

    static {
        for (int i = 0; i < 16; i++) {
            SAMPLERS.put("colortex" + i, "sampler2D");
        }
        for (String legacy : new String[]{"gcolor", "gdepth", "gnormal", "composite", "gaux1",
                "gaux2", "gaux3", "gaux4"}) {
            SAMPLERS.put(legacy, "sampler2D");
        }
        for (int i = 0; i < 3; i++) {
            SAMPLERS.put("depthtex" + i, "sampler2D");
        }
        SAMPLERS.put("dhDepthTex", "sampler2D");
        SAMPLERS.put("dhDepthTex0", "sampler2D");
        SAMPLERS.put("dhDepthTex1", "sampler2D");
        SAMPLERS.put("shadowtex0", "sampler2D");
        SAMPLERS.put("shadowtex1", "sampler2D");
        SAMPLERS.put("shadowcolor0", "sampler2D");
        SAMPLERS.put("shadowcolor1", "sampler2D");
        SAMPLERS.put("shadow", "sampler2D");
        SAMPLERS.put("noisetex", "sampler2D");
        SAMPLERS.put("gtexture", "sampler2D");
        SAMPLERS.put("lightmap", "sampler2D");
        SAMPLERS.put("normals", "sampler2D");
        SAMPLERS.put("specular", "sampler2D");

        UNIFORMS.put("BiomeTemp", "float");
        UNIFORMS.put("ModelView", "mat4");
        UNIFORMS.put("ModelViewInverse", "mat4");
        UNIFORMS.put("Projection", "mat4");
        UNIFORMS.put("ProjectionInverse", "mat4");
        UNIFORMS.put("alphaTestRef", "float");
        UNIFORMS.put("ambientLight", "float");
        UNIFORMS.put("aspectRatio", "float");
        UNIFORMS.put("atlasSize", "ivec2");
        UNIFORMS.put("bedrockLevel", "int");
        UNIFORMS.put("biome", "int");
        UNIFORMS.put("biome_category", "int");
        UNIFORMS.put("biome_precipitation", "int");
        UNIFORMS.put("blendFunc", "ivec4");
        UNIFORMS.put("blindFactor", "float");
        UNIFORMS.put("blindness", "float");
        UNIFORMS.put("blockEntityId", "int");
        UNIFORMS.put("burningSmooth", "float");
        UNIFORMS.put("cameraPosition", "vec3");
        UNIFORMS.put("cameraPositionFract", "vec3");
        UNIFORMS.put("cameraPositionInt", "ivec3");
        UNIFORMS.put("chunkOffset", "vec3");
        UNIFORMS.put("cloudHeight", "float");
        UNIFORMS.put("cloudTime", "float");
        UNIFORMS.put("constantMood", "float");
        UNIFORMS.put("currentColorSpace", "int");
        UNIFORMS.put("currentDate", "ivec3");
        UNIFORMS.put("currentPlayerAir", "float");
        UNIFORMS.put("currentPlayerArmor", "float");
        UNIFORMS.put("currentPlayerHealth", "float");
        UNIFORMS.put("currentPlayerHunger", "float");
        UNIFORMS.put("currentRenderedItemId", "int");
        UNIFORMS.put("currentSelectedBlockId", "int");
        UNIFORMS.put("currentSelectedBlockPos", "vec3");
        UNIFORMS.put("currentTime", "ivec3");
        UNIFORMS.put("currentYearTime", "ivec2");
        UNIFORMS.put("darknessFactor", "float");
        UNIFORMS.put("darknessLightFactor", "float");
        UNIFORMS.put("dawnDusk", "float");
        UNIFORMS.put("day", "float");
        UNIFORMS.put("decay", "float");
        UNIFORMS.put("dhFarPlane", "float");
        UNIFORMS.put("dhModelView", "mat4");
        UNIFORMS.put("dhModelViewInverse", "mat4");
        UNIFORMS.put("dhNearPlane", "float");
        UNIFORMS.put("dhPreviousModelView", "mat4");
        UNIFORMS.put("dhPreviousModelViewInverse", "mat4");
        UNIFORMS.put("dhPreviousProjection", "mat4");
        UNIFORMS.put("dhPreviousProjectionInverse", "mat4");
        UNIFORMS.put("dhProjection", "mat4");
        UNIFORMS.put("dhProjectionInverse", "mat4");
        UNIFORMS.put("dhRenderDistance", "int");
        UNIFORMS.put("effectStrength", "float");
        UNIFORMS.put("entityColor", "vec4");
        UNIFORMS.put("entityId", "int");
        UNIFORMS.put("eyeAltitude", "float");
        UNIFORMS.put("eyeBrightness", "ivec2");
        UNIFORMS.put("eyeBrightnessM", "float");
        UNIFORMS.put("eyeBrightnessSmooth", "ivec2");
        UNIFORMS.put("eyePosition", "vec3");
        UNIFORMS.put("far", "float");
        UNIFORMS.put("firstPersonCamera", "bool");
        UNIFORMS.put("fogColor", "vec3");
        UNIFORMS.put("fogDensity", "float");
        UNIFORMS.put("fogEnd", "float");
        UNIFORMS.put("fogMode", "int");
        UNIFORMS.put("fogShape", "int");
        UNIFORMS.put("fogStart", "float");
        UNIFORMS.put("frameCounter", "int");
        UNIFORMS.put("frameTime", "float");
        UNIFORMS.put("frameTimeCounter", "float");
        UNIFORMS.put("frameTimeSmooth", "float");
        UNIFORMS.put("gbufferModelView", "mat4");
        UNIFORMS.put("gbufferModelViewInverse", "mat4");
        UNIFORMS.put("gbufferPreviousModelView", "mat4");
        UNIFORMS.put("gbufferPreviousModelViewInverse", "mat4");
        UNIFORMS.put("gbufferPreviousProjection", "mat4");
        UNIFORMS.put("gbufferPreviousProjectionInverse", "mat4");
        UNIFORMS.put("gbufferProjection", "mat4");
        UNIFORMS.put("gbufferProjectionInverse", "mat4");
        UNIFORMS.put("gtextureSize", "ivec2");
        UNIFORMS.put("hasCeiling", "bool");
        UNIFORMS.put("hasSkylight", "bool");
        UNIFORMS.put("heightLimit", "int");
        UNIFORMS.put("heldBlockLightColor", "vec3");
        UNIFORMS.put("heldBlockLightColor2", "vec3");
        UNIFORMS.put("heldBlockLightValue", "int");
        UNIFORMS.put("heldBlockLightValue2", "int");
        UNIFORMS.put("heldItemId", "int");
        UNIFORMS.put("heldItemId2", "int");
        UNIFORMS.put("hideGUI", "bool");
        UNIFORMS.put("inSwamp", "float");
        UNIFORMS.put("iris_FogColor", "vec4");
        UNIFORMS.put("iris_FogDensity", "float");
        UNIFORMS.put("iris_FogEnd", "float");
        UNIFORMS.put("iris_FogStart", "float");
        UNIFORMS.put("iris_LineWidth", "float");
        UNIFORMS.put("iris_ScreenSize", "vec2");
        UNIFORMS.put("iris_currentAlphaTest", "float");
        UNIFORMS.put("isDry", "float");
        UNIFORMS.put("isEyeInCave", "float");
        UNIFORMS.put("isEyeInWater", "int");
        UNIFORMS.put("isPrecipitationRain", "float");
        UNIFORMS.put("isRainy", "float");
        UNIFORMS.put("isRightHanded", "bool");
        UNIFORMS.put("isSnowy", "float");
        UNIFORMS.put("isSpectator", "bool");
        UNIFORMS.put("is_burning", "bool");
        UNIFORMS.put("is_hurt", "bool");
        UNIFORMS.put("is_invisible", "bool");
        UNIFORMS.put("is_on_ground", "bool");
        UNIFORMS.put("is_sneaking", "bool");
        UNIFORMS.put("is_sprinting", "bool");
        UNIFORMS.put("lastFrameTime", "float");
        UNIFORMS.put("lightningBoltPosition", "vec4");
        UNIFORMS.put("logicalHeightLimit", "int");
        UNIFORMS.put("maxPlayerAir", "float");
        UNIFORMS.put("maxPlayerArmor", "float");
        UNIFORMS.put("maxPlayerHealth", "float");
        UNIFORMS.put("maxPlayerHunger", "float");
        UNIFORMS.put("modelViewMatrix", "mat4");
        UNIFORMS.put("modelViewMatrixInverse", "mat4");
        UNIFORMS.put("moonBrightness", "float");
        UNIFORMS.put("moonPhase", "int");
        UNIFORMS.put("near", "float");
        UNIFORMS.put("night", "float");
        UNIFORMS.put("nightVision", "float");
        UNIFORMS.put("normalMatrix", "mat3");
        UNIFORMS.put("pi", "float");
        UNIFORMS.put("playerBodyVector", "vec3");
        UNIFORMS.put("playerLookVector", "vec3");
        UNIFORMS.put("playerMood", "float");
        UNIFORMS.put("previousCameraPosition", "vec3");
        UNIFORMS.put("previousCameraPositionFract", "vec3");
        UNIFORMS.put("previousCameraPositionInt", "ivec3");
        UNIFORMS.put("projectionMatrix", "mat4");
        UNIFORMS.put("projectionMatrixInverse", "mat4");
        UNIFORMS.put("rainFactor", "float");
        UNIFORMS.put("rainStrength", "float");
        UNIFORMS.put("rainStrengthS", "float");
        UNIFORMS.put("rainStrengthS2", "float");
        UNIFORMS.put("rainStrengthShiningStars", "float");
        UNIFORMS.put("rainfall", "float");
        UNIFORMS.put("relativeEyePosition", "vec3");
        UNIFORMS.put("renderStage", "int");
        UNIFORMS.put("screenBrightness", "float");
        UNIFORMS.put("shadowAngle", "float");
        UNIFORMS.put("shadowFade", "float");
        UNIFORMS.put("shadowModelView", "mat4");
        UNIFORMS.put("shadowModelViewInverse", "mat4");
        UNIFORMS.put("shadowProjection", "mat4");
        UNIFORMS.put("shadowProjectionInverse", "mat4");
        UNIFORMS.put("shdFade", "float");
        UNIFORMS.put("skyColor", "vec3");
        UNIFORMS.put("sneakSmooth", "float");
        UNIFORMS.put("starter", "float");
        UNIFORMS.put("sunAngle", "float");
        UNIFORMS.put("temperature", "float");
        UNIFORMS.put("textureMatrix", "mat4");
        UNIFORMS.put("thunderStrength", "float");
        UNIFORMS.put("timeAngle", "float");
        UNIFORMS.put("timeBrightness", "float");
        UNIFORMS.put("touchmybody", "float");
        UNIFORMS.put("velocity", "float");
        UNIFORMS.put("viewHeight", "float");
        UNIFORMS.put("viewWidth", "float");
        UNIFORMS.put("wetness", "float");
        UNIFORMS.put("worldDay", "int");
        UNIFORMS.put("worldTime", "int");

        ATTRIBUTES.put("at_midBlock", "ivec3");
        ATTRIBUTES.put("at_tangent", "vec4");
        ATTRIBUTES.put("dhMaterialId", "int");
        ATTRIBUTES.put("mc_Entity", "vec4");
        ATTRIBUTES.put("mc_midTexCoord", "vec4");
        ATTRIBUTES.put("vaColor", "vec4");
        ATTRIBUTES.put("vaNormal", "vec3");
        ATTRIBUTES.put("vaPosition", "vec3");
        ATTRIBUTES.put("vaUV0", "vec2");
        ATTRIBUTES.put("vaUV1", "ivec2");
        ATTRIBUTES.put("vaUV2", "ivec2");
    }

    private ShaderUniforms() {
    }

    public static String declarations(String source, boolean vertexStage) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> sampler : SAMPLERS.entrySet()) {
            if (needed(source, sampler.getKey())) {
                out.append("uniform ").append(sampler.getValue()).append(' ')
                        .append(sampler.getKey()).append(";\n");
            }
        }
        for (Map.Entry<String, String> uniform : UNIFORMS.entrySet()) {
            if (needed(source, uniform.getKey())) {
                out.append("uniform ").append(uniform.getValue()).append(' ')
                        .append(uniform.getKey()).append(";\n");
            }
        }
        if (vertexStage) {
            for (Map.Entry<String, String> attribute : ATTRIBUTES.entrySet()) {
                if (needed(source, attribute.getKey())) {
                    out.append("in ").append(attribute.getValue()).append(' ')
                            .append(attribute.getKey()).append(";\n");
                }
            }
        }
        return out.toString();
    }

    private static boolean needed(String source, String name) {
        if (!Pattern.compile("\\b" + name + "\\b").matcher(source).find()) {
            return false;
        }
        return !Pattern.compile("(?:^|[;{}\\n])[ \\t]*(?:\\w+[ \\t]+)*"
                        + "(?!return[ \\t]|discard[ \\t])\\w+[ \\t]+" + name + "[ \\t]*[;,=\\[]",
                        Pattern.MULTILINE).matcher(source).find()
                && !Pattern.compile("\\(\\s*(?:\\w+\\s+)*\\w+\\s+" + name + "\\s*[,)]")
                        .matcher(source).find()
                && !Pattern.compile("(?:^|[;{}\\n])[ \\t]*(?:\\w+[ \\t]+)*\\w+[ \\t]+"
                        + name + "[ \\t]*\\(", Pattern.MULTILINE).matcher(source).find();
    }
}
