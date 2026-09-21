package com.voicecraft.vcmumbleserver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-branch transport runtime.
 *
 * The production/core branch replaces this with the embedded Mumble/Qt runtime.
 * This Java-only implementation exists so the Android lifecycle, TCP+UDP bind,
 * Endstone bridge, config persistence, foreground service and UI can be tested
 * before merging native Mumble integration into main.
 */
public final class NativeServer {
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger connectedClients = new AtomicInteger(0);
    private static final Set<String> proximityPlayers = ConcurrentHashMap.newKeySet();

    private static volatile boolean proximityEnabledForUi;
    private static volatile String lastError = "";
    private static volatile ServerSocket tcpServer;
    private static volatile DatagramSocket udpServer;
    private static volatile Thread tcpThread;
    private static volatile Thread udpThread;

    private NativeServer() {}

    public static synchronized int start(String iniPath, int fallbackPort, String nativeLibraryDir) {
        if (running.get()) return 0;
        lastError = "";
        try {
            ServerSocket tcp = new ServerSocket(fallbackPort);
            tcp.setReuseAddress(true);
            DatagramSocket udp = new DatagramSocket(fallbackPort);
            udp.setReuseAddress(true);
            tcpServer = tcp;
            udpServer = udp;
            running.set(true);
            tcpThread = new Thread(NativeServer::tcpLoop, "VCMumble-Smoke-TCP");
            udpThread = new Thread(NativeServer::udpLoop, "VCMumble-Smoke-UDP");
            tcpThread.setDaemon(true);
            udpThread.setDaemon(true);
            tcpThread.start();
            udpThread.start();
            return 0;
        } catch (IOException error) {
            lastError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            closeQuietly();
            running.set(false);
            return 1;
        }
    }

    private static void tcpLoop() {
        while (running.get()) {
            try {
                Socket socket = tcpServer.accept();
                connectedClients.incrementAndGet();
                Thread client = new Thread(() -> {
                    try (Socket s = socket) {
                        byte[] buf = new byte[1024];
                        while (running.get() && s.getInputStream().read(buf) >= 0) {
                            // Transport smoke-test only: consume bytes, never parse Mumble.
                        }
                    } catch (IOException ignored) {
                    } finally {
                        connectedClients.updateAndGet(value -> Math.max(0, value - 1));
                    }
                }, "VCMumble-Smoke-Client");
                client.setDaemon(true);
                client.start();
            } catch (SocketException error) {
                if (running.get()) lastError = "TCP: " + error.getMessage();
                return;
            } catch (IOException error) {
                if (running.get()) lastError = "TCP: " + error.getMessage();
            }
        }
    }

    private static void udpLoop() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running.get()) {
            try {
                udpServer.receive(packet);
                packet.setLength(buffer.length);
            } catch (SocketException error) {
                if (running.get()) lastError = "UDP: " + error.getMessage();
                return;
            } catch (IOException error) {
                if (running.get()) lastError = "UDP: " + error.getMessage();
            }
        }
    }

    public static synchronized void stop() {
        running.set(false);
        closeQuietly();
        connectedClients.set(0);
    }

    private static void closeQuietly() {
        ServerSocket tcp = tcpServer;
        DatagramSocket udp = udpServer;
        tcpServer = null;
        udpServer = null;
        try { if (tcp != null) tcp.close(); } catch (IOException ignored) {}
        if (udp != null) udp.close();
    }

    public static boolean isRunning() { return running.get(); }
    public static String lastError() { return lastError == null ? "" : lastError; }
    public static int connectedClients() { return connectedClients.get(); }
    public static boolean hasMumbleCore() { return false; }
    public static void markRuntimeLoaded() {}
    public static void markRuntimeUnloaded() {}
    public static boolean runtimeLoaded() { return true; }

    public static void setProximityEnabled(boolean enabled) {
        proximityEnabledForUi = enabled;
    }

    public static boolean isProximityEnabledForUi() { return proximityEnabledForUi; }
    public static void setProximityStaleTimeoutMs(long timeoutMs) {}

    public static void updatePlayerState(
            String mumbleName,
            String dimension,
            double x,
            double y,
            double z,
            float rangeBlocks,
            boolean voiceEnabled,
            int attenuationLevel
    ) {
        if (mumbleName != null && !mumbleName.trim().isEmpty()) proximityPlayers.add(mumbleName.trim());
    }

    public static void removePlayerState(String mumbleName) {
        if (mumbleName != null) proximityPlayers.remove(mumbleName.trim());
    }

    public static void clearPlayerStates() { proximityPlayers.clear(); }
    public static void touchPlayerStates() {}
    public static int proximityPlayerCount() { return proximityPlayers.size(); }
}
