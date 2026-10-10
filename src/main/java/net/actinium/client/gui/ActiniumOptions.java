package net.actinium.client.gui;

import net.actinium.ActiniumClient;
import net.actinium.ActiniumConfig;

public final class ActiniumOptions {
    private static final ActiniumOptions INSTANCE = new ActiniumOptions();

    public static ActiniumOptions get() {
        return INSTANCE;
    }

    public boolean interopEnabled = false;
    public boolean glContextBridge = true;
    public ActiniumClient.InteropMode interopMode = ActiniumClient.InteropMode.OFF;

    public boolean vsync = true;
    public boolean gpuCulling = true;
    public boolean multithreadedRecording = true;
    public int chunkBuildThreads = 0;

    public int uniformRingMegabytes = 8;
    public boolean persistentMapping = true;

    public boolean validationLayers = false;
    public boolean shaderDebugInfo = false;

    private ActiniumOptions() {
        load();
    }

    public void load() {
        if (!ActiniumConfig.SPEC.isLoaded()) {
            return;
        }
        interopEnabled = ActiniumConfig.interopEnabled.get();
        glContextBridge = ActiniumConfig.glContextBridge.get();
        interopMode = ActiniumConfig.interopMode.get();
        validationLayers = ActiniumConfig.validationLayers.get();
        shaderDebugInfo = ActiniumConfig.shaderDebugInfo.get();
        uniformRingMegabytes = ActiniumConfig.uniformRingMegabytes.get();
    }

    public void save() {
        if (!ActiniumConfig.SPEC.isLoaded()) {
            return;
        }
        ActiniumConfig.interopEnabled.set(interopEnabled);
        ActiniumConfig.glContextBridge.set(glContextBridge);
        ActiniumConfig.interopMode.set(interopMode);
        ActiniumConfig.validationLayers.set(validationLayers);
        ActiniumConfig.shaderDebugInfo.set(shaderDebugInfo);
        ActiniumConfig.uniformRingMegabytes.set(uniformRingMegabytes);
        ActiniumConfig.SPEC.save();
    }

    public int effectiveChunkBuildThreads() {
        if (chunkBuildThreads > 0) {
            return chunkBuildThreads;
        }

        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }
}
