package com.voicecraft.vcmumbleserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class EndstonePluginPackageTest {
    @Test
    public void latestReleaseSelectionIncludesPrereleaseAndSkipsDraftOrMissingWheel()
            throws Exception {
        String json = """
                [
                  {
                    "tag_name": "v0.6.0-beta.9",
                    "name": "Draft",
                    "draft": true,
                    "prerelease": true,
                    "published_at": "2026-09-24T10:00:00Z",
                    "assets": [
                      {
                        "name": "endstone_vc_mumble-0.4.3-py3-none-any.whl",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v9/plugin.whl"
                      }
                    ]
                  },
                  {
                    "tag_name": "v0.6.0",
                    "name": "Stable",
                    "draft": false,
                    "prerelease": false,
                    "published_at": "2026-09-22T10:00:00Z",
                    "assets": [
                      {
                        "name": "endstone_vc_mumble-0.4.1-py3-none-any.whl",
                        "browser_download_url": "https://github.com/example/repo/releases/download/vstable/plugin.whl"
                      }
                    ]
                  },
                  {
                    "tag_name": "v0.6.0-beta.10",
                    "name": "Newer release without plugin",
                    "draft": false,
                    "prerelease": true,
                    "published_at": "2026-09-25T10:00:00Z",
                    "assets": [
                      {
                        "name": "VC-Mumble-Server.apk",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v10/server.apk"
                      }
                    ]
                  },
                  {
                    "tag_name": "v0.6.0-beta.8",
                    "name": "Latest usable plugin",
                    "draft": false,
                    "prerelease": true,
                    "published_at": "2026-09-23T10:00:00Z",
                    "assets": [
                      {
                        "name": "endstone_vc_mumble-0.4.2-py3-none-any.whl",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v8/plugin.whl"
                      },
                      {
                        "name": "SHA256SUMS.txt",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v8/SHA256SUMS.txt"
                      }
                    ]
                  }
                ]
                """;

        EndstoneReleaseResolver.ReleaseInfo release =
                EndstoneReleaseResolver.parseLatest(json);

        assertEquals("v0.6.0-beta.8", release.tagName);
        assertEquals("endstone_vc_mumble-0.4.2-py3-none-any.whl", release.wheelName);
        assertTrue(release.hasChecksumAsset());
    }

    @Test
    public void newestPluginReleaseIsNotSilentlyReplacedWhenChecksumIsMissing()
            throws Exception {
        String json = """
                [
                  {
                    "tag_name": "v0.6.0-beta.9",
                    "draft": false,
                    "prerelease": true,
                    "published_at": "2026-09-24T10:00:00Z",
                    "assets": [
                      {
                        "name": "endstone_vc_mumble-0.4.3-py3-none-any.whl",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v9/plugin.whl"
                      }
                    ]
                  },
                  {
                    "tag_name": "v0.6.0-beta.8",
                    "draft": false,
                    "prerelease": true,
                    "published_at": "2026-09-23T10:00:00Z",
                    "assets": [
                      {
                        "name": "endstone_vc_mumble-0.4.2-py3-none-any.whl",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v8/plugin.whl"
                      },
                      {
                        "name": "SHA256SUMS.txt",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v8/SHA256SUMS.txt"
                      }
                    ]
                  }
                ]
                """;

        EndstoneReleaseResolver.ReleaseInfo release =
                EndstoneReleaseResolver.parseLatest(json);

        assertEquals("v0.6.0-beta.9", release.tagName);
        assertFalse(release.hasChecksumAsset());
    }

    @Test
    public void wheelPatchWritesSecretAndRebuildsRecordHashes() throws Exception {
        LinkedHashMap<String, byte[]> source = new LinkedHashMap<>();
        source.put(
                "endstone_vc_mumble/config.toml",
                ("[bridge]\n"
                        + "enabled = true\n"
                        + "host = \"0.0.0.0\"\n"
                        + "port = 27220\n"
                        + "secret = \"\"\n").getBytes(StandardCharsets.UTF_8)
        );
        source.put(
                "endstone_vc_mumble/plugin.py",
                "VALUE = 1\n".getBytes(StandardCharsets.UTF_8)
        );
        source.put(
                "endstone_vc_mumble-0.4.1.dist-info/METADATA",
                "Name: endstone-vc-mumble\nVersion: 0.4.1\n".getBytes(StandardCharsets.UTF_8)
        );
        source.put(
                "endstone_vc_mumble-0.4.1.dist-info/RECORD",
                "old,sha256=invalid,1\n".getBytes(StandardCharsets.UTF_8)
        );

        byte[] patched = EndstonePluginPackage.patchWheel(
                zip(source),
                "safe_secret-123_ABC"
        );
        Map<String, byte[]> entries = unzip(patched);

        String config = new String(
                entries.get("endstone_vc_mumble/config.toml"),
                StandardCharsets.UTF_8
        );
        assertTrue(config.contains("secret = \"safe_secret-123_ABC\""));

        String recordPath = "endstone_vc_mumble-0.4.1.dist-info/RECORD";
        String record = new String(entries.get(recordPath), StandardCharsets.UTF_8);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().equals(recordPath)) continue;
            String expected = entry.getKey()
                    + ",sha256=" + sha256UrlSafe(entry.getValue())
                    + "," + entry.getValue().length;
            assertTrue("Missing RECORD entry for " + entry.getKey(), record.contains(expected));
        }
        assertTrue(record.contains(recordPath + ",,"));
    }

    @Test
    public void signedWheelIsRejectedBeforeRewrite() throws Exception {
        LinkedHashMap<String, byte[]> source = new LinkedHashMap<>();
        source.put(
                "endstone_vc_mumble/config.toml",
                "[bridge]\nsecret = \"\"\n".getBytes(StandardCharsets.UTF_8)
        );
        source.put(
                "endstone_vc_mumble-0.4.1.dist-info/RECORD",
                new byte[0]
        );
        source.put(
                "endstone_vc_mumble-0.4.1.dist-info/RECORD.jws",
                "{}".getBytes(StandardCharsets.UTF_8)
        );

        try {
            EndstonePluginPackage.patchWheel(zip(source), "secret");
            fail("Expected signed wheel to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Signed wheel"));
        }
    }

    private static byte[] zip(LinkedHashMap<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                entries.put(entry.getName(), output.toByteArray());
            }
        }
        return entries;
    }

    private static String sha256UrlSafe(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
