package com.voicecraft.vcmumbleserver;

/**
 * JNI facade used by the embedded Mumble core build.
 *
 * QtService/androiddeployqt owns loading libvcserver.so and entering Mumble's
 * main(). This class deliberately does not call System.loadLibrary(); it only
 * exposes state/control JNI after QtService has loaded the runtime.
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

    // These names intentionally match the JNI exports compiled into libvcserver.
    public static native void setProximityStaleTimeoutMs(long timeoutMs);
    public static native void updatePlayerState(
            String mumbleName,
            String dimension,
            double x,
            double y,
            double z,
            float rangeBlocks
    );
    public static native void removePlayerState(String mumbleName);
    public static native void clearPlayerStates();

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
}
