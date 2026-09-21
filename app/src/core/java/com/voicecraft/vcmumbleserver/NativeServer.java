package com.voicecraft.vcmumbleserver;

/**
 * JNI facade used by the embedded Mumble core build.
 *
 * QtService/androiddeployqt owns loading libvcserver and entering Mumble's
 * main(). JNI_OnLoad in that library explicitly registers the methods below.
 */
public final class NativeServer {
    private static volatile boolean runtimeLoaded;
    private static volatile boolean proximityEnabledForUi;

    private NativeServer() {}

    private static native int startNative(String iniPath, int fallbackPort, String nativeLibraryDir);
    private static native void stopNative();
    private static native boolean isRunningNative();
    private static native String lastErrorNative();
    private static native int connectedClientsNative();
    private static native void setProximityEnabledNative(boolean enabled);
    private static native int proximityPlayerCountNative();

    private static native void setProximityStaleTimeoutMsNative(long timeoutMs);
    private static native void updatePlayerStateNative(
            String mumbleName,
            String dimension,
            double x,
            double y,
            double z,
            float rangeBlocks,
            boolean voiceEnabled,
            int attenuationLevel
    );
    private static native void removePlayerStateNative(String mumbleName);
    private static native void clearPlayerStatesNative();
    private static native void touchPlayerStatesNative();

    public static synchronized int start(String iniPath, int fallbackPort, String nativeLibraryDir) {
        if (!runtimeLoaded) return 1;
        return startNative(iniPath, fallbackPort, nativeLibraryDir);
    }

    public static synchronized void stop() {
        if (runtimeLoaded) stopNative();
    }

    public static boolean isRunning() {
        return runtimeLoaded && isRunningNative();
    }

    public static String lastError() {
        if (!runtimeLoaded) return "";
        String value = lastErrorNative();
        return value == null ? "" : value;
    }

    public static int connectedClients() {
        return runtimeLoaded ? connectedClientsNative() : 0;
    }

    public static boolean hasMumbleCore() { return true; }

    public static void markRuntimeLoaded() {
        runtimeLoaded = true;
    }

    public static void markRuntimeUnloaded() {
        runtimeLoaded = false;
        proximityEnabledForUi = false;
    }

    public static boolean runtimeLoaded() { return runtimeLoaded; }

    public static void setProximityEnabled(boolean enabled) {
        proximityEnabledForUi = enabled;
        if (runtimeLoaded) setProximityEnabledNative(enabled);
    }

    public static boolean isProximityEnabledForUi() {
        return proximityEnabledForUi;
    }

    public static int proximityPlayerCount() {
        return runtimeLoaded ? proximityPlayerCountNative() : 0;
    }

    public static void setProximityStaleTimeoutMs(long timeoutMs) {
        if (runtimeLoaded) setProximityStaleTimeoutMsNative(timeoutMs);
    }

    public static void updatePlayerState(
            String mumbleName,
            String dimension,
            double x,
            double y,
            double z,
            float rangeBlocks,
            boolean voiceEnabled,
            int attenuationLevel
    ) {
        if (runtimeLoaded) {
            updatePlayerStateNative(mumbleName, dimension, x, y, z, rangeBlocks, voiceEnabled, attenuationLevel);
        }
    }

    public static void removePlayerState(String mumbleName) {
        if (runtimeLoaded) removePlayerStateNative(mumbleName);
    }

    public static void clearPlayerStates() {
        if (runtimeLoaded) clearPlayerStatesNative();
    }

    public static void touchPlayerStates() {
        if (runtimeLoaded) touchPlayerStatesNative();
    }
}
