package com.voicecraft.vcmumbleserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class McsvBedrockAddonInstallerTest {
    @Test
    public void parsesBehaviorPackManifest() throws Exception {
        String manifest = """
                {
                  "format_version": 2,
                  "header": {
                    "name": "VC Mumble BP",
                    "uuid": "123e4567-e89b-42d3-a456-426614174000",
                    "version": [2, 7, 6]
                  },
                  "modules": [
                    {
                      "type": "data",
                      "uuid": "223e4567-e89b-42d3-a456-426614174000",
                      "version": [2, 7, 6]
                    },
                    {
                      "type": "script",
                      "uuid": "323e4567-e89b-42d3-a456-426614174000",
                      "version": [2, 7, 6]
                    }
                  ]
                }
                """;

        McsvBedrockAddonInstaller.PackInfo pack =
                McsvBedrockAddonInstaller.parsePackManifest(
                        "/stage/VC Mumble BP",
                        manifest
                );

        assertEquals(McsvBedrockAddonInstaller.PackKind.BEHAVIOR, pack.kind);
        assertEquals("123e4567-e89b-42d3-a456-426614174000", pack.uuid);
        assertEquals(2, pack.version[0]);
        assertEquals(7, pack.version[1]);
        assertEquals(6, pack.version[2]);
    }

    @Test
    public void parsesResourcePackManifest() throws Exception {
        String manifest = """
                {
                  "format_version": 2,
                  "header": {
                    "name": "VC Mumble RP",
                    "uuid": "423e4567-e89b-42d3-a456-426614174000",
                    "version": [2, 7, 6]
                  },
                  "modules": [
                    {
                      "type": "resources",
                      "uuid": "523e4567-e89b-42d3-a456-426614174000",
                      "version": [2, 7, 6]
                    }
                  ]
                }
                """;

        McsvBedrockAddonInstaller.PackInfo pack =
                McsvBedrockAddonInstaller.parsePackManifest(
                        "/stage/VC Mumble RP",
                        manifest
                );

        assertEquals(McsvBedrockAddonInstaller.PackKind.RESOURCE, pack.kind);
        assertEquals("423e4567-e89b-42d3-a456-426614174000", pack.uuid);
    }

    @Test
    public void mergeWorldPackConfigPreservesOtherPacksAndReplacesSameUuid()
            throws Exception {
        JSONArray current = new JSONArray("""
                [
                  {
                    "pack_id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                    "version": [1, 0, 0]
                  },
                  {
                    "pack_id": "123e4567-e89b-42d3-a456-426614174000",
                    "version": [2, 7, 5]
                  }
                ]
                """);

        McsvBedrockAddonInstaller.PackInfo pack =
                new McsvBedrockAddonInstaller.PackInfo(
                        "/stage/bp",
                        "123e4567-e89b-42d3-a456-426614174000",
                        new int[]{2, 7, 6},
                        McsvBedrockAddonInstaller.PackKind.BEHAVIOR
                );

        JSONArray merged =
                McsvBedrockAddonInstaller.mergeWorldPackConfig(current, pack);

        assertEquals(2, merged.length());

        JSONObject first = merged.getJSONObject(0);
        assertEquals(
                "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                first.getString("pack_id")
        );

        JSONObject installed = merged.getJSONObject(1);
        assertEquals(pack.uuid, installed.getString("pack_id"));
        JSONArray version = installed.getJSONArray("version");
        assertEquals(2, version.getInt(0));
        assertEquals(7, version.getInt(1));
        assertEquals(6, version.getInt(2));
    }

    @Test
    public void parsesActiveBedrockWorldName() throws Exception {
        String properties = """
                server-name=VC Test
                gamemode=survival
                level-name=Bedrock level
                server-port=19132
                """;

        assertEquals(
                "Bedrock level",
                McsvBedrockAddonInstaller.parseLevelName(properties)
        );
    }

    @Test
    public void rejectsUnsafeWorldName() {
        try {
            McsvBedrockAddonInstaller.parseLevelName(
                    "level-name=../other-world\n"
            );
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("ไม่ปลอดภัย"));
            return;
        }
        throw new AssertionError("Expected unsafe level-name to be rejected");
    }
}
