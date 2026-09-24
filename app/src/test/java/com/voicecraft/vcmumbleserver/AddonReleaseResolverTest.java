package com.voicecraft.vcmumbleserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AddonReleaseResolverTest {
    @Test
    public void selectsNewestPublishedReleaseContainingItemMicAddon() throws Exception {
        String json = """
                [
                  {
                    "tag_name": "v0.6.0-beta.10",
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
                    "tag_name": "v0.6.0-beta.9",
                    "draft": true,
                    "prerelease": true,
                    "published_at": "2026-09-24T10:00:00Z",
                    "assets": [
                      {
                        "name": "VC_Mumble_ItemMic_v2.7.8.mcaddon",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v9/addon.mcaddon"
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
                        "name": "Other_Addon.mcaddon",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v8/other.mcaddon"
                      },
                      {
                        "name": "VC_Mumble_ItemMic_v2.7.7.mcaddon",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v8/VC_Mumble_ItemMic_v2.7.7.mcaddon"
                      },
                      {
                        "name": "SHA256SUMS.txt",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v8/SHA256SUMS.txt"
                      }
                    ]
                  },
                  {
                    "tag_name": "v0.6.0",
                    "draft": false,
                    "prerelease": false,
                    "published_at": "2026-09-22T10:00:00Z",
                    "assets": [
                      {
                        "name": "VC_Mumble_ItemMic_v2.7.6.mcaddon",
                        "browser_download_url": "https://github.com/example/repo/releases/download/stable/addon.mcaddon"
                      }
                    ]
                  }
                ]
                """;

        AddonReleaseResolver.ReleaseInfo release = AddonReleaseResolver.parseLatest(json);

        assertEquals("v0.6.0-beta.8", release.tagName);
        assertEquals("VC_Mumble_ItemMic_v2.7.7.mcaddon", release.addonName);
        assertTrue(release.hasChecksumAsset());
    }

    @Test
    public void newestAddonReleaseDoesNotFallBackWhenChecksumMissing() throws Exception {
        String json = """
                [
                  {
                    "tag_name": "v0.6.0-beta.9",
                    "draft": false,
                    "prerelease": true,
                    "published_at": "2026-09-24T10:00:00Z",
                    "assets": [
                      {
                        "name": "VC_Mumble_ItemMic_v2.7.8.mcaddon",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v9/addon.mcaddon"
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
                        "name": "VC_Mumble_ItemMic_v2.7.7.mcaddon",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v8/addon.mcaddon"
                      },
                      {
                        "name": "SHA256SUMS.txt",
                        "browser_download_url": "https://github.com/example/repo/releases/download/v8/SHA256SUMS.txt"
                      }
                    ]
                  }
                ]
                """;

        AddonReleaseResolver.ReleaseInfo release = AddonReleaseResolver.parseLatest(json);

        assertEquals("v0.6.0-beta.9", release.tagName);
        assertFalse(release.hasChecksumAsset());
    }

    @Test
    public void checksumManifestFindsExactAddonName() throws Exception {
        String expected =
                "8f6d6e046ed3e8bdbe809bc5f7d2cc87d6a9f0450bb7c2b4ab1b2a544b978aa3";
        String manifest =
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  other.file\n"
                        + expected + "  VC_Mumble_ItemMic_v2.7.6.mcaddon\n";

        assertEquals(
                expected,
                VerifiedAddonPackage.findExpectedSha256(
                        manifest,
                        "VC_Mumble_ItemMic_v2.7.6.mcaddon"
                )
        );
    }
}
