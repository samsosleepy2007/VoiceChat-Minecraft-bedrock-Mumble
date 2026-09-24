package com.voicecraft.vcmumbleserver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;

public final class VCMumbleBridgeClientFramingTest {
    @Test
    public void preservesPartialJsonAcrossSocketTimeouts() throws Exception {
        ScriptedInputStream input = new ScriptedInputStream();
        input.add("{\"type\":\"player_state\",\"uuid\":\"9b4eaafd-");
        input.addTimeout();
        input.add("eae5-3408-8761-ac26180a326b\",\"x\":-1.0552186965942383}\n");
        input.add("{\"type\":\"heartbeat\",\"online\":2");
        input.addTimeout();
        input.add("}\n");

        VCMumbleBridgeClient.TimeoutSafeNdjsonReader reader =
                new VCMumbleBridgeClient.TimeoutSafeNdjsonReader(input);

        try {
            reader.readLine();
        } catch (SocketTimeoutException expected) {
            // Prefix must remain buffered inside the reader.
        }

        assertEquals(
                "{\"type\":\"player_state\",\"uuid\":\"9b4eaafd-eae5-3408-8761-ac26180a326b\",\"x\":-1.0552186965942383}",
                reader.readLine()
        );

        try {
            reader.readLine();
        } catch (SocketTimeoutException expected) {
            // The second partial frame must also survive a timeout.
        }

        assertEquals(
                "{\"type\":\"heartbeat\",\"online\":2}",
                reader.readLine()
        );
    }

    @Test
    public void returnsMultipleCompleteFramesFromOneRead() throws Exception {
        ScriptedInputStream input = new ScriptedInputStream();
        input.add("{\"type\":\"sync_begin\"}\n{\"type\":\"sync_end\"}\n");

        VCMumbleBridgeClient.TimeoutSafeNdjsonReader reader =
                new VCMumbleBridgeClient.TimeoutSafeNdjsonReader(input);

        assertEquals("{\"type\":\"sync_begin\"}", reader.readLine());
        assertEquals("{\"type\":\"sync_end\"}", reader.readLine());
    }

    private static final class ScriptedInputStream extends InputStream {
        private final Queue<Object> actions = new ArrayDeque<>();

        void add(String value) {
            actions.add(value.getBytes(StandardCharsets.UTF_8));
        }

        void addTimeout() {
            actions.add(new SocketTimeoutException("simulated timeout"));
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int result = read(single, 0, 1);
            if (result < 0) return -1;
            return single[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Object action = actions.peek();
            if (action == null) return -1;
            if (action instanceof SocketTimeoutException) {
                actions.remove();
                throw (SocketTimeoutException) action;
            }

            byte[] bytes = (byte[]) action;
            int count = Math.min(length, bytes.length);
            System.arraycopy(bytes, 0, buffer, offset, count);
            if (count == bytes.length) {
                actions.remove();
            } else {
                byte[] remaining = new byte[bytes.length - count];
                System.arraycopy(bytes, count, remaining, 0, remaining.length);
                actions.remove();
                actions.add(remaining);
            }
            return count;
        }
    }
}
