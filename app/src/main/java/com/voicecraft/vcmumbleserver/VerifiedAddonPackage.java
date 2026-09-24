package com.voicecraft.vcmumbleserver;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class VerifiedAddonPackage {
    private static final int MAX_ADDON_BYTES = 64 * 1024 * 1024;
    private static final int MAX_CHECKSUM_BYTES = 256 * 1024;

    private VerifiedAddonPackage() {}

    static DownloadedAddon downloadAndVerify(AddonReleaseResolver.ReleaseInfo release)
            throws IOException {
        if (release == null) {
            throw new IOException("Addon release metadata is missing");
        }
        if (!release.hasChecksumAsset()) {
            throw new IOException("Latest Addon release is missing SHA256SUMS.txt");
        }

        byte[] addon = download(release.addonUrl, MAX_ADDON_BYTES);
        byte[] checksum = download(release.checksumUrl, MAX_CHECKSUM_BYTES);
        String expected = findExpectedSha256(
                new String(checksum, StandardCharsets.UTF_8),
                release.addonName
        );
        String actual = sha256Hex(addon);
        if (!constantTimeHexEquals(expected, actual)) {
            throw new IOException(
                    "SHA-256 mismatch for " + release.addonName
                            + "; expected=" + expected
                            + ", actual=" + actual
            );
        }
        return new DownloadedAddon(release, addon, actual);
    }

    private static byte[] download(String url, int maxBytes) throws IOException {
        HttpURLConnection connection = EndstoneReleaseResolver.open(url);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download returned HTTP " + status);
            }
            return EndstoneReleaseResolver.readLimited(
                    connection.getInputStream(),
                    maxBytes
            );
        } finally {
            connection.disconnect();
        }
    }

    static String findExpectedSha256(String checksumText, String fileName)
            throws IOException {
        for (String rawLine : checksumText.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = firstWhitespace(line);
            if (separator <= 0) continue;

            String hash = line.substring(0, separator).trim().toLowerCase(Locale.US);
            String listedName = line.substring(separator).trim();
            if (listedName.startsWith("*")) listedName = listedName.substring(1);
            if (!listedName.equals(fileName)) continue;

            if (!hash.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid SHA-256 entry for " + fileName);
            }
            return hash;
        }
        throw new IOException("SHA256SUMS.txt does not contain " + fileName);
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return i;
        }
        return -1;
    }

    private static String sha256Hex(byte[] data) throws IOException {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }

        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            output.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return output.toString();
    }

    private static boolean constantTimeHexEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    static final class DownloadedAddon {
        final AddonReleaseResolver.ReleaseInfo release;
        final byte[] bytes;
        final String sha256;

        DownloadedAddon(
                AddonReleaseResolver.ReleaseInfo release,
                byte[] bytes,
                String sha256
        ) {
            this.release = release;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }
}
