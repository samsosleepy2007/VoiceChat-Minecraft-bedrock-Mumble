package com.voicecraft.vcmumbleserver;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Experimental compatibility probe for the official PortWarp Linux ARM64 CLI.
 *
 * The PortWarp binary is intentionally NOT bundled in the APK. The user starts
 * this probe explicitly; it downloads the public vendor archive and checksum,
 * verifies SHA-256, extracts pwrp into app-private storage, and only runs
 * "pwrp version". No account credentials or tunnel commands are used here.
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

    private static void downloadToFile(String url, File target, long maxBytes) throws IOException {
        HttpURLConnection connection = open(url);
        long declared = connection.getContentLengthLong();
        if (declared > maxBytes) {
            connection.disconnect();
            throw new IOException("Download is unexpectedly large: " + declared);
        }

        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(target, false)) {
            copyLimited(input, output, maxBytes);
        } finally {
            connection.disconnect();
        }
    }

    private static String downloadText(String url, long maxBytes) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyLimited(input, output, maxBytes);
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String value) throws IOException {
        URL url = new URL(value);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "VC-Mumble-PortWarp-Compatibility-Probe/1");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + status + " for " + value);
        }
        return connection;
    }

    private static long copyLimited(InputStream input, java.io.OutputStream output, long max)
            throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total += count;
            if (total > max) throw new IOException("Download/extract exceeded " + max + " bytes");
            output.write(buffer, 0, count);
        }
        output.flush();
        return total;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value.toString();
    }

    private static ArchiveSpec findLatestArm64Archive(String text) {
        ArchiveSpec best = null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^([0-9a-fA-F]{64})\\s+pwrp-([0-9]+)\\.([0-9]+)\\.([0-9]+)-linux-arm64\\.tar\\.gz$"
        );
        for (String raw : text.split("\\n")) {
            String line = raw.trim();
            java.util.regex.Matcher matcher = pattern.matcher(line);
            if (!matcher.matches()) continue;

            int major = Integer.parseInt(matcher.group(2));
            int minor = Integer.parseInt(matcher.group(3));
            int patch = Integer.parseInt(matcher.group(4));
            String fileName = "pwrp-" + major + "." + minor + "." + patch
                    + "-linux-arm64.tar.gz";
            ArchiveSpec candidate = new ArchiveSpec(
                    fileName,
                    matcher.group(1).toLowerCase(Locale.US),
                    major,
                    minor,
                    patch
            );
            if (best == null || candidate.isNewerThan(best)) best = candidate;
        }
        return best;
    }

    private static final class ArchiveSpec {
        final String fileName;
        final String sha256;
        final int major;
        final int minor;
        final int patch;

        ArchiveSpec(String fileName, String sha256, int major, int minor, int patch) {
            this.fileName = fileName;
            this.sha256 = sha256;
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        boolean isNewerThan(ArchiveSpec other) {
            if (major != other.major) return major > other.major;
            if (minor != other.minor) return minor > other.minor;
            return patch > other.patch;
        }
    }

    private static void extractNamedTarGzEntry(File archive, String wanted, File output)
            throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new FileInputStream(archive))) {
            byte[] header = new byte[512];
            while (true) {
                int headerBytes = readFully(gzip, header, 0, header.length);
                if (headerBytes == 0) break;
                if (headerBytes != 512) throw new IOException("Truncated tar header");
                if (allZero(header)) break;

                String name = cString(header, 0, 100);
                long size = parseTarOctal(header, 124, 12);
                if (size < 0 || size > MAX_BINARY_BYTES) {
                    throw new IOException("Invalid tar entry size for " + name + ": " + size);
                }

                boolean match = wanted.equals(name) || name.endsWith("/" + wanted);
                if (match) {
                    try (FileOutputStream target = new FileOutputStream(output, false)) {
                        copyExactly(gzip, target, size);
                    }
                } else {
                    skipExactly(gzip, size);
                }

                long padding = (512 - (size % 512)) % 512;
                skipExactly(gzip, padding);
                if (match) {
                    if (!output.isFile() || output.length() == 0) {
                        throw new IOException("Extracted pwrp is empty");
                    }
                    return;
                }
            }
        }
        throw new IOException("pwrp entry not found in official archive");
    }

    private static int readFully(InputStream input, byte[] buffer, int offset, int length)
            throws IOException {
        int total = 0;
        while (total < length) {
            int count = input.read(buffer, offset + total, length - total);
            if (count < 0) break;
            if (count > 0) total += count;
        }
        return total;
    }

    private static void copyExactly(InputStream input, java.io.OutputStream output, long length)
            throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("Unexpected EOF in tar entry");
            if (count == 0) continue;
            output.write(buffer, 0, count);
            remaining -= count;
        }
        output.flush();
    }

    private static void skipExactly(InputStream input, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("Unexpected EOF while skipping tar entry");
            remaining -= count;
        }
    }

    private static boolean allZero(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static long parseTarOctal(byte[] data, int offset, int length) throws IOException {
        long value = 0;
        boolean sawDigit = false;
        for (int i = offset; i < offset + length; i++) {
            int c = data[i] & 0xff;
            if (c == 0 || c == ' ') {
                if (sawDigit) break;
                continue;
            }
            if (c < '0' || c > '7') throw new IOException("Invalid tar size");
            sawDigit = true;
            value = (value << 3) + (c - '0');
        }
        return value;
    }
}
