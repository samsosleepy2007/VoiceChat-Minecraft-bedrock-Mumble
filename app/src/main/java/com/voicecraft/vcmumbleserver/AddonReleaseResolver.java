package com.voicecraft.vcmumbleserver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

final class AddonReleaseResolver {
    private static final String RELEASES_API =
            "https://api.github.com/repos/samsosleepy2007/VoiceChat-Minecraft-bedrock-Mumble/releases?per_page=100";
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;

    private AddonReleaseResolver() {}

    static ReleaseInfo resolveLatest() throws IOException {
        HttpURLConnection connection = EndstoneReleaseResolver.open(RELEASES_API);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub Releases returned HTTP " + status);
            }
            String json = new String(
                    EndstoneReleaseResolver.readLimited(
                            connection.getInputStream(),
                            MAX_METADATA_BYTES
                    ),
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

                String addonName = null;
                String addonUrl = null;
                String checksumUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;

                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.optJSONObject(j);
                    if (asset == null) continue;

                    String name = asset.optString("name", "");
                    String url = asset.optString("browser_download_url", "");
                    if (isItemMicAddon(name) && isTrustedDownloadUrl(url)) {
                        addonName = name;
                        addonUrl = url;
                    } else if ("SHA256SUMS.txt".equals(name) && isTrustedDownloadUrl(url)) {
                        checksumUrl = url;
                    }
                }

                if (addonUrl == null) continue;
                ReleaseInfo candidate = new ReleaseInfo(
                        release.optString("tag_name", ""),
                        release.optString("name", ""),
                        publishedAt,
                        addonName,
                        addonUrl,
                        checksumUrl
                );
                if (newest == null || candidate.publishedAt.compareTo(newest.publishedAt) > 0) {
                    newest = candidate;
                }
            }

            if (newest == null) {
                throw new IOException(
                        "No published GitHub Release contains a VC Mumble Item Mic .mcaddon asset"
                );
            }
            return newest;
        } catch (JSONException error) {
            throw new IOException("Could not parse GitHub Releases metadata", error);
        }
    }

    private static boolean isItemMicAddon(String name) {
        return name.startsWith("VC_Mumble_ItemMic_") && name.endsWith(".mcaddon");
    }

    private static boolean isTrustedDownloadUrl(String rawUrl) {
        try {
            java.net.URL url = new java.net.URL(rawUrl);
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
        final String addonName;
        final String addonUrl;
        final String checksumUrl;

        ReleaseInfo(
                String tagName,
                String releaseName,
                String publishedAt,
                String addonName,
                String addonUrl,
                String checksumUrl
        ) {
            this.tagName = tagName;
            this.releaseName = releaseName;
            this.publishedAt = publishedAt;
            this.addonName = addonName;
            this.addonUrl = addonUrl;
            this.checksumUrl = checksumUrl;
        }

        boolean hasChecksumAsset() {
            return checksumUrl != null && !checksumUrl.isEmpty();
        }
    }
}
