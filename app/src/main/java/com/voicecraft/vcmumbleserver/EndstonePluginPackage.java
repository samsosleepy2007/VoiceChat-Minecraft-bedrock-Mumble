package com.voicecraft.vcmumbleserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class EndstonePluginPackage {
    private static final int MAX_WHEEL_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CHECKSUM_BYTES = 256 * 1024;
    private static final int MAX_UNPACKED_BYTES = 32 * 1024 * 1024;
    private static final String CONFIG_PATH = "endstone_vc_mumble/config.toml";
    private static final Pattern SECRET_LINE =
            Pattern.compile("(?m)^\\s*secret\\s*=\\s*.*$");

    private EndstonePluginPackage() {}

    static ConfiguredPlugin downloadAndConfigure(
            EndstoneReleaseResolver.ReleaseInfo release,
            String bridgeSecret
    ) throws IOException {
        if (release == null) throw new IOException("Endstone release metadata is missing");
        if (bridgeSecret == null || bridgeSecret.isEmpty()) {
            throw new IOException("Bridge Secret is empty");
        }
        if (!release.hasChecksumAsset()) {
            throw new IOException("Latest Endstone release is missing SHA256SUMS.txt");
        }

        byte[] wheel = download(release.wheelUrl, MAX_WHEEL_BYTES);
        byte[] checksumFile = download(release.checksumUrl, MAX_CHECKSUM_BYTES);
        String expectedSha256 = findExpectedSha256(
                new String(checksumFile, StandardCharsets.UTF_8),
                release.wheelName
        );
        String downloadedSha256 = sha256Hex(wheel);
        if (!constantTimeHexEquals(expectedSha256, downloadedSha256)) {
            throw new IOException(
                    "SHA-256 mismatch for " + release.wheelName
                            + "; expected=" + expectedSha256
                            + ", actual=" + downloadedSha256
            );
        }

        byte[] configured = patchWheel(wheel, bridgeSecret);
        return new ConfiguredPlugin(
                release,
                configured,
                downloadedSha256,
                sha256Hex(configured)
        );
    }

    static String buildConfigToml(String bridgeSecret, int bridgePort, int voiceRange) {
        int safePort = bridgePort >= 1 && bridgePort <= 65535 ? bridgePort : 27220;
        int safeRange = voiceRange >= 1 && voiceRange <= 1000 ? voiceRange : 30;
        int maxRange = Math.max(150, safeRange);
        return "[tracking]\n"
                + "interval_ticks = 2\n"
                + "position_epsilon = 0.05\n"
                + "rotation_epsilon = 1.0\n"
                + "heartbeat_seconds = 15\n"
                + "\n"
                + "[bridge]\n"
                + "enabled = true\n"
                + "host = \"0.0.0.0\"\n"
                + "port = " + safePort + "\n"
                + "secret = \"" + tomlEscape(bridgeSecret) + "\"\n"
                + "max_queue = 4096\n"
                + "max_frame_bytes = 262144\n"
                + "auth_timeout_seconds = 10\n"
                + "\n"
                + "[voice]\n"
                + "default_range = " + safeRange + "\n"
                + "max_range = " + maxRange + "\n"
                + "default_attenuation_level = 2\n";
    }

    private static byte[] download(String url, int limit) throws IOException {
        HttpURLConnection connection = EndstoneReleaseResolver.open(url);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download returned HTTP " + status);
            }
            return EndstoneReleaseResolver.readLimited(connection.getInputStream(), limit);
        } finally {
            connection.disconnect();
        }
    }

    private static String findExpectedSha256(String checksumText, String fileName)
            throws IOException {
        for (String rawLine : checksumText.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = firstWhitespace(line);
            if (separator <= 0) continue;
            String hash = line.substring(0, separator).trim().toLowerCase(Locale.US);
            String listedName = line.substring(separator).trim();
            if (listedName.startsWith("*")) listedName = listedName.substring(1);
            if (listedName.equals(fileName)) {
                if (!hash.matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid SHA-256 entry for " + fileName);
                }
                return hash;
            }
        }
        throw new IOException("SHA256SUMS.txt does not contain " + fileName);
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return i;
        }
        return -1;
    }

    private static byte[] patchWheel(byte[] wheel, String bridgeSecret) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        String recordPath = null;
        int unpackedBytes = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(wheel))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty() || entry.isDirectory()) continue;
                if (name.contains("../") || name.startsWith("/") || name.startsWith("\\")) {
                    throw new IOException("Unsafe path inside Endstone wheel");
                }
                if (name.endsWith(".dist-info/RECORD.jws")
                        || name.endsWith(".dist-info/RECORD.p7s")) {
                    throw new IOException("Signed wheel cannot be safely rewritten");
                }

                byte[] data = readZipEntryLimited(
                        zip,
                        MAX_UNPACKED_BYTES - unpackedBytes
                );
                unpackedBytes += data.length;
                if (unpackedBytes > MAX_UNPACKED_BYTES) {
                    throw new IOException("Endstone wheel expands beyond safety limit");
                }
                if (entries.put(name, data) != null) {
                    throw new IOException("Duplicate path inside Endstone wheel: " + name);
                }
                if (name.endsWith(".dist-info/RECORD")) {
                    recordPath = name;
                }
            }
        }

        if (!entries.containsKey(CONFIG_PATH)) {
            throw new IOException("Endstone wheel does not contain " + CONFIG_PATH);
        }
        if (recordPath == null) {
            throw new IOException("Endstone wheel does not contain .dist-info/RECORD");
        }

        String originalConfig = new String(entries.get(CONFIG_PATH), StandardCharsets.UTF_8);
        Matcher matcher = SECRET_LINE.matcher(originalConfig);
        if (!matcher.find()) {
            throw new IOException("Endstone config.toml does not contain bridge.secret");
        }
        String replacement = "secret = \"" + tomlEscape(bridgeSecret) + "\"";
        String configured = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
        entries.put(CONFIG_PATH, configured.getBytes(StandardCharsets.UTF_8));

        entries.remove(recordPath);
        entries.put(recordPath, buildRecord(entries, recordPath));

        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(item.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private static byte[] readZipEntryLimited(ZipInputStream zip, int maxBytes)
            throws IOException {
        if (maxBytes <= 0) {
            throw new IOException("Endstone wheel expands beyond safety limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Endstone wheel expands beyond safety limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static byte[] buildRecord(LinkedHashMap<String, byte[]> entries, String recordPath)
            throws IOException {
        StringBuilder record = new StringBuilder();
        for (Map.Entry<String, byte[]> item : entries.entrySet()) {
            String path = item.getKey();
            if (path.equals(recordPath)) continue;
            byte[] data = item.getValue();
            record.append(csv(path))
                    .append(",sha256=")
                    .append(sha256UrlSafe(data))
                    .append(",")
                    .append(data.length)
                    .append("\n");
        }
        record.append(csv(recordPath)).append(",,\n");
        return record.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String tomlEscape(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String sha256Hex(byte[] data) throws IOException {
        byte[] digest = sha256(data);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static String sha256UrlSafe(byte[] data) throws IOException {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(data));
    }

    private static byte[] sha256(byte[] data) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean constantTimeHexEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    static final class ConfiguredPlugin {
        final EndstoneReleaseResolver.ReleaseInfo release;
        final byte[] bytes;
        final String sourceSha256;
        final String configuredSha256;

        ConfiguredPlugin(
                EndstoneReleaseResolver.ReleaseInfo release,
                byte[] bytes,
                String sourceSha256,
                String configuredSha256
        ) {
            this.release = release;
            this.bytes = bytes;
            this.sourceSha256 = sourceSha256;
            this.configuredSha256 = configuredSha256;
        }
    }
}
