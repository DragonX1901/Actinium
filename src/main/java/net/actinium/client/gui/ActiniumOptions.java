package net.actinium.client.gui;

import net.actinium.ActiniumClient;
import net.actinium.ActiniumConfig;

/**
 * Actinium's own settings, held in memory and mirrored to the config file.
 *
 * <p>Plain mutable fields on purpose. The settings screen edits a pending copy and only
 * writes through on Apply, so this object never needs to defend itself against partial edits.
 */
public final class ActiniumOptions {

    private static final ActiniumOptions INSTANCE = new ActiniumOptions();

    public static ActiniumOptions get() {
        return INSTANCE;
    }

    // --- Interop ---------------------------------------------------------
    public ActiniumClient.InteropMode interopMode = ActiniumClient.InteropMode.BRIDGE;

    // --- Rendering -------------------------------------------------------
    public boolean vsync = true;
    public boolean gpuCulling = true;
    public boolean multithreadedRecording = true;
    public int chunkBuildThreads = 0; // 0 = derive from available processors

    // --- Memory ----------------------------------------------------------
    public int uniformRingMegabytes = 8;
    public boolean persistentMapping = true;

    // --- Debug -----------------------------------------------------------
    public boolean validationLayers = false;
    public boolean shaderDebugInfo = false;

    private ActiniumOptions() {
        load();
    }

    /** Pulls the config file's values in. Called once, and again after a config reload. */
    public void load() {
        if (!ActiniumConfig.SPEC.isLoaded()) {
            return; // config file not parsed yet; defaults stand until it is
        }
        interopMode = ActiniumConfig.interopMode.get();
        validationLayers = ActiniumConfig.validationLayers.get();
        shaderDebugInfo = ActiniumConfig.shaderDebugInfo.get();
        uniformRingMegabytes = ActiniumConfig.uniformRingMegabytes.get();
    }

    /** Writes back to the config file. Options with no config entry stay in memory only. */
    public void save() {
        if (!ActiniumConfig.SPEC.isLoaded()) {
            return;
        }
        ActiniumConfig.interopMode.set(interopMode);
        ActiniumConfig.validationLayers.set(validationLayers);
        ActiniumConfig.shaderDebugInfo.set(shaderDebugInfo);
        ActiniumConfig.uniformRingMegabytes.set(uniformRingMegabytes);
        ActiniumConfig.SPEC.save();
    }

    /** @return the chunk build thread count actually used, resolving 0 to a derived default. */
    public int effectiveChunkBuildThreads() {
        if (chunkBuildThreads > 0) {
            return chunkBuildThreads;
        }
        // Leave the render thread and the main thread room to breathe.
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }
}
