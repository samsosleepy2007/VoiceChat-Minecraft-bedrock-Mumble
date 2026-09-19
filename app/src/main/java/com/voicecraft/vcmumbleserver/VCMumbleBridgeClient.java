package com.voicecraft.vcmumbleserver;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.atomic.AtomicBoolean;

final class VCMumbleBridgeClient {
    interface Listener {
        void onBridgeStatus(String status, boolean connected, int trackedPlayers);
    }

    private final ServerConfig config;
    private final Listener listener;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private Thread thread;
    private volatile Socket socket;
    private boolean syncing;
    private String lastStatus = "";
    private boolean lastConnected;
    private int lastTracked = -1;

    VCMumbleBridgeClient(ServerConfig config, Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    void start() {
        if (thread != null) return;
        stopped.set(false);
        thread = new Thread(this::runLoop, "VCMumble-DirectBridge");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stopped.set(true);
        Socket current = socket;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) {}
        }
        Thread currentThread = thread;
        if (currentThread != null && currentThread.isAlive()) {
            try { currentThread.join(1500L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        thread = null;
        socket = null;
    }

    private void runLoop() {
        while (!stopped.get()) {
            try {
                runConnection();
            } catch (Exception error) {
                if (!stopped.get()) {
                    String message = error.getMessage();
                    onDisconnected("Minecraft bridge error: " + (message == null ? error.getClass().getSimpleName() : message));
                }
            }
            if (!stopped.get()) sleepReconnect();
        }
    }

    private void runConnection() throws IOException, JSONException {
        Socket s = new Socket();
        socket = s;
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(config.bridgeHost.trim(), config.bridgePort), 8000);
        s.setSoTimeout(1000);

        BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

        JSONObject hello = new JSONObject();
        hello.put("type", "hello");
        hello.put("role", "vc_mumble_server");
        hello.put("protocol", 1);
        hello.put("app", "VC Mumble Server");
        hello.put("appVersion", "0.4.0-alpha02");
        send(writer, hello);

        long helloDeadline = System.currentTimeMillis() + 10_000L;
        boolean authenticated = false;
        while (!stopped.get() && !authenticated && System.currentTimeMillis() < helloDeadline) {
            try {
                String line = reader.readLine();
                if (line == null) throw new IOException("bridge closed during authentication");
                JSONObject data = new JSONObject(line);
                String type = data.optString("type", "");
                if ("auth_challenge".equals(type) && data.optInt("protocol", 0) == 1) {
                    String nonce = data.optString("nonce", "");
                    if (nonce.isEmpty()) throw new IOException("bridge sent empty challenge");
                    JSONObject response = new JSONObject();
                    response.put("type", "auth_response");
                    response.put("hmac", computeHmac(config.bridgeSecret, "vc-mumble-v1:" + nonce));
                    send(writer, response);
                } else if ("hello_ok".equals(type) && data.optInt("protocol", 0) == 1) {
                    authenticated = true;
                    NativeServer.clearPlayerStates();
                    notifyStatus("Minecraft bridge connected • syncing", true);
                    JSONObject request = new JSONObject();
                    request.put("type", "request_snapshot");
                    send(writer, request);
                } else if ("hello_error".equals(type)) {
                    throw new IOException("authentication rejected");
                }
            } catch (SocketTimeoutException ignored) {}
        }
        if (!authenticated) throw new IOException("bridge authentication timed out");

        while (!stopped.get()) {
            try {
                String line = reader.readLine();
                if (line == null) throw new IOException("bridge closed");
                handleMessage(new JSONObject(line));
            } catch (SocketTimeoutException ignored) {
                // Socket timeout is only used so stop() remains responsive.
            }
        }
    }

    private void handleMessage(JSONObject data) {
        String type = data.optString("type", "");
        switch (type) {
            case "sync_begin":
                syncing = true;
                NativeServer.clearPlayerStates();
                notifyStatus("Minecraft bridge connected • syncing", true);
                break;
            case "sync_end":
                syncing = false;
                notifyStatus("Minecraft bridge connected • proximity ready", true);
                break;
            case "player_state":
                applyPlayerState(data);
                break;
            case "player_leave":
                removePlayer(data);
                break;
            case "heartbeat":
                notifyStatus(syncing ? "Minecraft bridge connected • syncing" : "Minecraft bridge connected", true);
                break;
            default:
                break;
        }
    }

    private void applyPlayerState(JSONObject data) {
        String minecraftName = data.optString("name", "").trim();
        String mumbleName = data.optString("mumbleName", minecraftName).trim();
        String dimension = data.optString("dimension", "").trim();
        double x = data.optDouble("x", Double.NaN);
        double y = data.optDouble("y", Double.NaN);
        double z = data.optDouble("z", Double.NaN);
        int range = data.optInt("voiceRange", config.voiceRange);
        if (mumbleName.isEmpty() || dimension.isEmpty()) return;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        if (Math.abs(x) > 30_000_000.0 || Math.abs(z) > 30_000_000.0 || y < -4096.0 || y > 4096.0) return;
        if (range < 1) range = config.voiceRange;

        NativeServer.updatePlayerState(mumbleName, dimension, x, y, z, range);
        if (!syncing) notifyStatus("Minecraft bridge connected • proximity active", true);
    }

    private void removePlayer(JSONObject data) {
        String minecraftName = data.optString("name", "").trim();
        String mumbleName = data.optString("mumbleName", minecraftName).trim();
        if (!mumbleName.isEmpty()) NativeServer.removePlayerState(mumbleName);
        notifyStatus("Minecraft bridge connected • proximity active", true);
    }

    private void onDisconnected(String reason) {
        syncing = false;
        socket = null;
        NativeServer.clearPlayerStates();
        notifyStatus(reason + " • retrying", false);
    }

    private void sleepReconnect() {
        for (int i = 0; i < 50 && !stopped.get(); i++) {
            try { Thread.sleep(100L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static String computeHmac(String secret, String message) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (GeneralSecurityException error) {
            throw new IOException("could not authenticate bridge", error);
        }
    }

    private static void send(BufferedWriter writer, JSONObject payload) throws IOException {
        writer.write(payload.toString());
        writer.write('\n');
        writer.flush();
    }

    private synchronized void notifyStatus(String status, boolean connected) {
        int tracked = NativeServer.proximityPlayerCount();
        if (status.equals(lastStatus) && connected == lastConnected && tracked == lastTracked) return;
        lastStatus = status;
        lastConnected = connected;
        lastTracked = tracked;
        if (listener != null) listener.onBridgeStatus(status, connected, tracked);
    }
}
