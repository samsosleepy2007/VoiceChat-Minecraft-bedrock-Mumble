package com.voicecraft.vcmumbleserver;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Compatibility probe for the official PortWarp Linux ARM64 CLI packaged in
 * the APK as libpwrp_exec.so. Android 10+ forbids execve() from writable app
 * home directories, so the binary is checksum-verified during CI and executed
 * only from ApplicationInfo.nativeLibraryDir.
 */
public final class PortWarpExecProbe {
    public interface Callback {
        void onComplete(boolean success, String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final long MAX_ARCHIVE_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_BINARY_BYTES = 32L * 1024L * 1024L;

    private PortWarpExecProbe() {}

    public static void run(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            String result;
            try {
                result = runBlocking(app);
                ok = true;
            } catch (Throwable error) {
                result = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                ServerLog.append(app, "PORTWARP", "Execution probe FAILED: " + result);
            }

            final boolean success = ok;
            final String message = result;
            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).runOnUiThread(
                        () -> callback.onComplete(success, message)
                );
            } else {
                callback.onComplete(success, message);
            }
        }, "PortWarp-Exec-Probe").start();
    }

    private static String runBlocking(Context context) throws Exception {
        File binary = ensureInstalled(context);
        File root = rootDir(context);
        File home = homeDir(context);

        ServerLog.append(context, "PORTWARP",
                "Attempting Android exec; path=" + binary.getAbsolutePath()
                        + ", bytes=" + binary.length()
                        + ", canExecute=" + binary.canExecute());

        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "version");
        builder.redirectErrorStream(true);
        builder.directory(root);
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());

        final Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new IOException("Android exec failed for static ARM64 pwrp: "
                    + error.getMessage(), error);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0 && output.size() < 64 * 1024) {
                        output.write(buffer, 0, Math.min(count, 64 * 1024 - output.size()));
                    }
                }
            } catch (IOException ignored) {
            }
        }, "PortWarp-Probe-Output");
        reader.start();

        boolean exited = process.waitFor(12, TimeUnit.SECONDS);
        if (!exited) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            throw new IOException("pwrp version timed out after 12 seconds");
        }
        reader.join(1000);

        int exit = process.exitValue();
        String text = output.toString(StandardCharsets.UTF_8.name()).trim()
                .replace("\r", " ").replace("\n", " | ");
        ServerLog.append(context, "PORTWARP",
                "pwrp version exit=" + exit + ", output=" + text);
        if (exit != 0) {
            throw new IOException("pwrp version exited " + exit + ": " + text);
        }
        if (text.isEmpty()) text = "pwrp exited 0 with no text output";
        return "PortWarp CLI executed on Android: " + text;
    }

    static File ensureInstalled(Context context) throws Exception {
        if (!isArm64()) {
            throw new IllegalStateException("PortWarp requires an ARM64 Android device");
        }

        ApplicationInfo info = context.getApplicationInfo();
        File binary = new File(info.nativeLibraryDir, "libpwrp_exec.so");
        if (!binary.isFile()) {
            throw new IOException("Packaged PortWarp runtime is missing: "
                    + binary.getAbsolutePath());
        }
        if (!binary.canExecute()) {
            throw new IOException("Packaged PortWarp runtime is not executable: "
                    + binary.getAbsolutePath());
        }

        File root = rootDir(context);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Unable to create PortWarp runtime directory");
        }
        File home = homeDir(context);
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("Unable to create PortWarp HOME directory");
        }

        ServerLog.append(context, "PORTWARP",
                "Using APK-packaged PortWarp runtime from nativeLibraryDir: "
                        + binary.getAbsolutePath());
        return binary;
    }

    static File rootDir(Context context) {
        return new File(context.getNoBackupFilesDir(), "portwarp");
    }

    static File homeDir(Context context) {
        return new File(rootDir(context), "home");
    }

    private static boolean isArm64() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

}
