package com.voicecraft.vcmumbleserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.junit.Test;

import java.io.IOException;

public final class McsvInstallerTest {
    @Test
    public void prefersConfiguredBridgePortWhenMcsvAllocatedIt() throws Exception {
        JSONArray ports = new JSONArray("[19132,27220,30001]");

        assertEquals(
                27220,
                McsvInstaller.chooseBridgePort(ports, 19132, 27220)
        );
    }

    @Test
    public void fallsBackToFirstAllocatedNonPrimaryPort() throws Exception {
        JSONArray ports = new JSONArray("[19132,31000,32000]");

        assertEquals(
                31000,
                McsvInstaller.chooseBridgePort(ports, 19132, 27220)
        );
    }

    @Test
    public void rejectsServerWithoutSpareBridgePort() throws Exception {
        JSONArray ports = new JSONArray("[19132]");

        try {
            McsvInstaller.chooseBridgePort(ports, 19132, 27220);
            fail("Expected missing spare port to fail");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void recognizesRuntimeStatesThatNeedSafeStop() {
        org.junit.Assert.assertTrue(McsvInstaller.isRuntimeActive("running"));
        org.junit.Assert.assertTrue(McsvInstaller.isRuntimeActive("starting"));
        org.junit.Assert.assertFalse(McsvInstaller.isRuntimeActive("offline"));
        org.junit.Assert.assertFalse(McsvInstaller.isRuntimeActive("stopped"));
    }

    @Test
    public void rejectsNonMcsvApiKeyBeforeNetworkCall() {
        try {
            new McsvApiClient("not-a-key");
            fail("Expected invalid MCSV key prefix to fail");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
