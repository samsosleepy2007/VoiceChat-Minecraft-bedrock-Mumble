package com.voicecraft.vcmumbleserver;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
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

        File launcher = launcherFile(context);
        File resolver = prepareResolverFile(context);

        ProcessBuilder builder = new ProcessBuilder(
                launcher.getAbsolutePath(),
                resolver.getAbsolutePath(),
                binary.getAbsolutePath(),
                "version"
        );
        builder.redirectErrorStream(true);
        builder.directory(root);
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        configureTlsTrust(context, builder);

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

        File launcher = launcherFile(context);
        if (!launcher.isFile() || !launcher.canExecute()) {
            throw new IOException("Packaged PortWarp DNS launcher is unavailable: "
                    + launcher.getAbsolutePath());
        }

        ServerLog.append(context, "PORTWARP",
                "Using APK-packaged PortWarp runtime from nativeLibraryDir: "
                        + binary.getAbsolutePath());
        return binary;
    }

    static File launcherFile(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir,
                "libpwrp_dns_launcher_exec.so");
    }

    static File prepareResolverFile(Context context) throws IOException {
        File resolver = new File(rootDir(context), "resolv.conf");
        Set<String> servers = new LinkedHashSet<>();

        ConnectivityManager connectivity =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) {
            Network active = connectivity.getActiveNetwork();
            LinkProperties properties =
                    active == null ? null : connectivity.getLinkProperties(active);
            if (properties != null) {
                for (InetAddress address : properties.getDnsServers()) {
                    if (address == null || address.isAnyLocalAddress()
                            || address.isLoopbackAddress()) {
                        continue;
                    }
                    String value = address.getHostAddress();
                    if (value != null && !value.trim().isEmpty()) {
                        servers.add(value.trim());
                    }
                }
            }
        }

        if (servers.isEmpty()) {
            // Fallback only when Android exposes no active DNS servers.
            servers.add("1.1.1.1");
            servers.add("8.8.8.8");
            servers.add("2606:4700:4700::1111");
            servers.add("2001:4860:4860::8888");
        }

        StringBuilder text = new StringBuilder();
        for (String server : servers) {
            text.append("nameserver ").append(server).append('\n');
        }
        text.append("options timeout:2 attempts:2\n");

        try (FileOutputStream output = new FileOutputStream(resolver, false)) {
            output.write(text.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        if (!resolver.setReadable(true, true)) {
            throw new IOException("Unable to mark PortWarp resolver file readable");
        }

        ServerLog.append(context, "PORTWARP",
                "Prepared Android DNS resolver snapshot with " + servers.size()
                        + " server(s); fd-path=/proc/self/fd/10");
        return resolver;
    }

    static void configureTlsTrust(Context context, ProcessBuilder builder)
            throws IOException {
        String[] candidates = new String[] {
                "/apex/com.android.conscrypt/cacerts",
                "/system/etc/security/cacerts"
        };

        StringBuilder dirs = new StringBuilder();
        int usable = 0;
        for (String candidate : candidates) {
            File dir = new File(candidate);
            File[] entries = dir.listFiles();
            if (!dir.isDirectory() || entries == null || entries.length == 0) {
                continue;
            }
            if (dirs.length() > 0) dirs.append(File.pathSeparatorChar);
            dirs.append(dir.getAbsolutePath());
            usable++;
        }

        if (usable == 0) {
            throw new IOException("Android CA trust store is unavailable");
        }

        builder.environment().put("SSL_CERT_DIR", dirs.toString());
        builder.environment().remove("SSL_CERT_FILE");
        ServerLog.append(context, "PORTWARP",
                "Configured Go TLS trust from Android CA store(s): " + usable);
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
