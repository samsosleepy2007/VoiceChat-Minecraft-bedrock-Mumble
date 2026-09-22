package com.voicecraft.vcmumbleserver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class EndstoneReleaseResolver {
    private static final String RELEASES_API =
            "https://api.github.com/repos/samsosleepy2007/VoiceChat-Minecraft-bedrock-Mumble/releases?per_page=100";
    private static final String ACCEPT = "application/vnd.github+json";
    private static final String API_VERSION = "2022-11-28";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;

    private EndstoneReleaseResolver() {}

    static ReleaseInfo resolveLatest() throws IOException {
        HttpURLConnection connection = open(RELEASES_API);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub Releases returned HTTP " + status);
            }

            String json = new String(
                    readLimited(connection.getInputStream(), MAX_METADATA_BYTES),
                    StandardCharsets.UTF_8
            );
            return parseLatest(json);
        } finally {
            connection.disconnect();
        }
    }

    static ReleaseInfo parseLatest(String json) throws IOException {
        try {
            JSONArray releases = new JSONArray(json);
            ReleaseInfo newest = null;

            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || release.optBoolean("draft", false)) continue;

                String publishedAt = release.optString("published_at", "");
                if (publishedAt.isEmpty()) continue;

                String wheelName = null;
                String wheelUrl = null;
                String checksumUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;

                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.optJSONObject(j);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    String url = asset.optString("browser_download_url", "");
                    if (isEndstoneWheel(name) && isTrustedDownloadUrl(url)) {
                        wheelName = name;
                        wheelUrl = url;
                    } else if ("SHA256SUMS.txt".equals(name) && isTrustedDownloadUrl(url)) {
                        checksumUrl = url;
                    }
                }

                if (wheelUrl == null) continue;
                ReleaseInfo candidate = new ReleaseInfo(
                        release.optString("tag_name", ""),
                        release.optString("name", ""),
                        publishedAt,
                        wheelName,
                        wheelUrl,
                        checksumUrl
                );
                if (newest == null || candidate.publishedAt.compareTo(newest.publishedAt) > 0) {
                    newest = candidate;
                }
            }

            if (newest == null) {
                throw new IOException("No published GitHub Release contains an Endstone .whl asset");
            }
            return newest;
        } catch (JSONException error) {
            throw new IOException("Could not parse GitHub Releases metadata", error);
        }
    }

    static HttpURLConnection open(String rawUrl) throws IOException {
        URL url = new URL(rawUrl);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Refusing non-HTTPS download URL");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", ACCEPT);
        connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION);
        connection.setRequestProperty("User-Agent", "VC-Mumble-Server-Android");
        return connection;
    }

    static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Downloaded data exceeds safety limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean isEndstoneWheel(String name) {
        return name.startsWith("endstone_vc_mumble-") && name.endsWith(".whl");
    }

    private static boolean isTrustedDownloadUrl(String rawUrl) {
        try {
            URL url = new URL(rawUrl);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && "github.com".equalsIgnoreCase(url.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    static final class ReleaseInfo {
        final String tagName;
        final String releaseName;
        final String publishedAt;
        final String wheelName;
        final String wheelUrl;
        final String checksumUrl;

        ReleaseInfo(
                String tagName,
                String releaseName,
                String publishedAt,
                String wheelName,
                String wheelUrl,
                String checksumUrl
        ) {
            this.tagName = tagName;
            this.releaseName = releaseName;
            this.publishedAt = publishedAt;
            this.wheelName = wheelName;
            this.wheelUrl = wheelUrl;
            this.checksumUrl = checksumUrl;
        }

        boolean hasChecksumAsset() {
            return checksumUrl != null && !checksumUrl.isEmpty();
        }
    }
}
