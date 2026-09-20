package com.voicecraft.vcmumbleserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.qtproject.qt.android.RestartableQtService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Core build service.
 *
 * Qt's Android loader owns loading Qt, its plugins, and the native main
 * library. RestartableQtService keeps that startup path but avoids Qt's
 * process-wide System.exit(0) on service destruction.
 */
public final class MumbleServerService extends RestartableQtService {
    public static final String ACTION_START = "com.voicecraft.vcmumbleserver.START";
    public static final String ACTION_STOP = "com.voicecraft.vcmumbleserver.STOP";
    public static final String ACTION_STATE = "com.voicecraft.vcmumbleserver.STATE";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_BRIDGE = "bridge";
    public static final String EXTRA_TRACKED = "tracked";
    public static final String EXTRA_BACKGROUND = "background";

    private static final int NOTIFICATION_ID = 4107;
    private static final String CHANNEL_ID = "vc_mumble_server";
    private static final int STARTUP_PROBE_MAX_ATTEMPTS = 30;
    private static final long STARTUP_PROBE_INTERVAL_MS = 500L;
    private static final int STARTUP_PROBE_CONNECT_TIMEOUT_MS = 350;
    private static final long BACKGROUND_HEALTH_INTERVAL_MS = 60_000L;
    private static final int BACKGROUND_HEALTH_CONNECT_TIMEOUT_MS = 750;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private VCMumbleBridgeClient bridgeClient;
    private String serverAddress = "";
    private String bridgeStatus = "Bridge waiting for config";
    private int trackedPlayers;
    private int serverPort = 64738;
    private int startupProbeAttempt;
    private boolean terminateProcessOnDestroy;
    private boolean nativeStopRequested;
    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;
    private String runtimeProtectionStatus = "Runtime protection starting";
    private int backgroundHealthFailures;
    private int backgroundHealthChecks;

    @Override
    public void onCreate() {
        createNotificationChannel();
        ServerLog.append(this, "SERVICE", "onCreate; sdk=" + Build.VERSION.SDK_INT);

        // startForegroundService() gives the service only a short window to
        // become foreground. QtServiceBase.onCreate() synchronously loads Qt
        // and waits for native service setup, so enter foreground *before*
        // invoking the Qt loader to avoid ForegroundServiceDidNotStartInTime.
        Notification bootNotification = buildNotification("Starting Mumble runtime…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, bootNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, bootNotification);
        }
        acquireRuntimeProtection();

        // The inherited Qt loader starts the AAR-packaged Qt runtime and
        // libvcserver on Qt's native application thread.
        super.onCreate();
        if (isQtRuntimeStarted()) {
            NativeServer.markRuntimeLoaded();
            ServerLog.append(this, "QT", "Qt runtime reports started; NativeServer marked loaded");
        } else {
            NativeServer.markRuntimeUnloaded();
            String qtError = qtStartupError();
            ServerLog.append(
                    this,
                    "QT",
                    qtError.isEmpty()
                            ? "Qt runtime did not report started after service startup"
                            : "Qt runtime startup failed: " + qtError
            );
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireRuntimeProtection() {
        try {
            PowerManager powerManager = getSystemService(PowerManager.class);
            if (powerManager != null) {
                cpuWakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        getPackageName() + ":MumbleServer"
                );
                cpuWakeLock.setReferenceCounted(false);
                cpuWakeLock.acquire();
                ServerLog.append(this, "POWER", "CPU partial wake lock acquired");
            }

            if (Build.VERSION.SDK_INT <= 33) {
                WifiManager wifiManager =
                        (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifiManager != null) {
                    wifiLock = wifiManager.createWifiLock(
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            getPackageName() + ":MumbleServerWifi"
                    );
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                    ServerLog.append(this, "POWER", "Legacy high-performance Wi-Fi lock acquired");
                }
            } else {
                ServerLog.append(this, "POWER",
                        "Android 14+ uses platform-managed Wi-Fi power mode; legacy lock skipped");
            }
        } catch (Throwable error) {
            ServerLog.append(this, "POWER",
                    "Runtime protection warning: " + error.getClass().getSimpleName()
                            + ": " + String.valueOf(error.getMessage()));
        }
        updateRuntimeProtectionStatus(false);
    }

    private void releaseRuntimeProtection() {
        if (wifiLock != null) {
            try {
                if (wifiLock.isHeld()) wifiLock.release();
            } catch (Throwable ignored) {
            }
            wifiLock = null;
        }
        if (cpuWakeLock != null) {
            try {
                if (cpuWakeLock.isHeld()) cpuWakeLock.release();
            } catch (Throwable ignored) {
            }
            cpuWakeLock = null;
        }
        runtimeProtectionStatus = "Runtime protection released";
    }

    private void updateRuntimeProtectionStatus(boolean healthy) {
        boolean cpuHeld = cpuWakeLock != null && cpuWakeLock.isHeld();
        boolean wifiHeld = wifiLock != null && wifiLock.isHeld();
        String wifiState = Build.VERSION.SDK_INT <= 33
                ? (wifiHeld ? "Wi-Fi protected" : "Wi-Fi lock unavailable")
                : "Wi-Fi managed by Android";
        runtimeProtectionStatus =
                (cpuHeld ? "CPU protected" : "CPU lock unavailable")
                        + " • " + wifiState
                        + (healthy ? " • Health OK" : "");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean stickyRestart = intent == null;
        String action = stickyRestart ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            ServerRuntimeState.setShouldRun(this, false);
            stopCoreAndSelf();
            return START_NOT_STICKY;
        }

        if (stickyRestart && !ServerRuntimeState.shouldRun(this)) {
            ServerLog.append(this, "SERVICE",
                    "Sticky restart ignored because server_should_run=false");
            releaseRuntimeProtection();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        ServerRuntimeState.setShouldRun(this, true);
        ServerConfig config = ServerConfig.load(this);

        if (!NativeServer.runtimeLoaded()) {
            String qtError = qtStartupError();
            String message = qtError.isEmpty()
                    ? "Qt runtime did not start"
                    : "Qt startup failed: " + qtError;
            ServerLog.append(this, "ERROR", message);
            serverAddress = NetworkUtil.bestLanIpv4() + ":" + config.port;
            bridgeStatus = "Minecraft proximity off";
            publish(false, message);
            updateNotification(message);
            ServerRuntimeState.setShouldRun(this, false);
            terminateProcessOnDestroy = true;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        ServerLog.append(
                this,
                "SERVICE",
                "Start requested; port=" + config.port
                        + ", maxUsers=" + config.maxUsers
                        + ", proximity=" + config.proximityEnabled
                        + ", passwordSet=" + (config.password != null && !config.password.isEmpty())
                        + ", ini=" + new java.io.File(getFilesDir(), "mumble/mumble-server.ini").getAbsolutePath()
                        + ", nativeLibDir=" + getApplicationInfo().nativeLibraryDir
        );
        serverPort = config.port;
        startupProbeAttempt = 0;
        updateNotification("Starting on port " + config.port + "…");

        boolean proximityActive = true;
        try {
            ServerLog.append(this, "JNI", "setProximityStaleTimeoutMs begin");
            NativeServer.setProximityStaleTimeoutMs(15000L);
            ServerLog.append(this, "JNI", "setProximityStaleTimeoutMs OK");

            ServerLog.append(this, "JNI", "clearPlayerStates begin");
            NativeServer.clearPlayerStates();
            ServerLog.append(this, "JNI", "clearPlayerStates OK");

            ServerLog.append(this, "JNI", "setProximityEnabled(" + proximityActive + ") begin");
            NativeServer.setProximityEnabled(proximityActive);
            ServerLog.append(this, "JNI", "setProximityEnabled OK");
        } catch (Throwable error) {
            String message = "JNI startup failed: " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage());
            ServerLog.append(this, "ERROR", message);
            serverAddress = NetworkUtil.bestLanIpv4() + ":" + config.port;
            bridgeStatus = "Minecraft proximity off";
            publish(false, message);
            updateNotification(message);
            ServerRuntimeState.setShouldRun(this, false);
            terminateProcessOnDestroy = true;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        serverAddress = NetworkUtil.bestLanIpv4() + ":" + config.port;
        if (config.hasUsableBridgeConfig()) {
            startRelay(config);
        } else {
            bridgeStatus = "Bridge config missing • proximity waiting";
        }

        // Qt startup is asynchronous relative to Android service callbacks.
        publish(true, "Starting • " + serverAddress);
        updateNotification("Starting • " + serverAddress);
        handler.removeCallbacks(coreHealthCheck);
        ServerLog.append(this, "SERVICE", "Scheduling TCP readiness probe in "
                + STARTUP_PROBE_INTERVAL_MS + "ms");
        handler.postDelayed(coreHealthCheck, STARTUP_PROBE_INTERVAL_MS);
        return START_STICKY;
    }

    private final Runnable coreHealthCheck = new Runnable() {
        @Override public void run() {
            final int attempt = ++startupProbeAttempt;
            final int port = serverPort;

            Thread probeThread = new Thread(() -> {
                boolean tcpReady = probeTcpListener(port, attempt);
                handler.post(() -> handleProbeResult(attempt, tcpReady));
            }, "VCMumble-TCP-Probe");
            probeThread.setDaemon(true);
            probeThread.start();
        }
    };

    private boolean probeTcpListener(int port, int attempt) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress("127.0.0.1", port),
                    STARTUP_PROBE_CONNECT_TIMEOUT_MS
            );
            boolean connected = socket.isConnected();
            ServerLog.append(
                    this,
                    "PROBE",
                    "TCP 127.0.0.1:" + port + " attempt " + attempt + " => "
                            + (connected ? "OPEN" : "NOT_CONNECTED")
            );
            return connected;
        } catch (IOException error) {
            ServerLog.append(
                    this,
                    "PROBE",
                    "TCP 127.0.0.1:" + port + " attempt " + attempt + " => "
                            + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())
            );
            return false;
        }
    }

    private void handleProbeResult(int attempt, boolean tcpReady) {
        if (tcpReady) {
            ServerLog.append(this, "SERVICE", "Mumble TCP listener is ready at " + serverAddress
                    + " after " + attempt + " probe attempt(s)");
            backgroundHealthFailures = 0;
            backgroundHealthChecks = 0;
            updateRuntimeProtectionStatus(true);
            updateNotification(null);
            publish(true, serverAddress);
            scheduleBackgroundHealthCheck();
            return;
        }

        if (attempt < STARTUP_PROBE_MAX_ATTEMPTS) {
            String starting = "Starting • " + serverAddress
                    + " • waiting for TCP (" + attempt + "/" + STARTUP_PROBE_MAX_ATTEMPTS + ")";
            publish(true, starting);
            updateNotification(starting);
            handler.postDelayed(coreHealthCheck, STARTUP_PROBE_INTERVAL_MS);
            return;
        }

        String error = NativeServer.lastError();
        String message = error.isEmpty()
                ? "Mumble core loaded but TCP port " + serverPort + " did not open"
                : error;
        ServerLog.append(this, "ERROR", message);
        publish(false, message);
        updateNotification(message);
        ServerLog.append(this, "SERVICE",
                "Startup failed; stopping isolated Mumble process so the next Start is clean");
        ServerRuntimeState.setShouldRun(this, false);
        terminateProcessOnDestroy = true;
        stopRelay();
        requestNativeStop();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        ServerLog.append(this, "SERVICE", "onDestroy; runtimeLoaded=" + NativeServer.runtimeLoaded()
                + ", terminateProcess=" + terminateProcessOnDestroy);
        handler.removeCallbacks(coreHealthCheck);
        handler.removeCallbacks(backgroundHealthCheck);
        stopRelay();
        requestNativeStop();
        NativeServer.markRuntimeUnloaded();
        releaseRuntimeProtection();
        super.onDestroy();

        if (terminateProcessOnDestroy) {
            final int pid = android.os.Process.myPid();
            ServerLog.append(this, "SERVICE",
                    "Qt shutdown complete; isolated Mumble process " + pid + " will exit");
            handler.postDelayed(() -> android.os.Process.killProcess(pid), 250L);
        }
    }

    private void stopCoreAndSelf() {
        ServerLog.append(this, "SERVICE", "Stop requested; clean process restart will be required");
        ServerRuntimeState.setShouldRun(this, false);
        terminateProcessOnDestroy = true;
        handler.removeCallbacks(coreHealthCheck);
        handler.removeCallbacks(backgroundHealthCheck);
        stopRelay();
        requestNativeStop();
        publish(false, "Stopped");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void requestNativeStop() {
        if (nativeStopRequested) return;
        nativeStopRequested = true;

        if (!NativeServer.runtimeLoaded()) return;
        try {
            NativeServer.setProximityEnabled(false);
            NativeServer.clearPlayerStates();
            NativeServer.stop();
            ServerLog.append(this, "SERVICE", "Native Mumble stop requested");
        } catch (Throwable error) {
            ServerLog.append(this, "ERROR",
                    "Native stop failed: " + error.getClass().getSimpleName()
                            + ": " + String.valueOf(error.getMessage()));
        }
    }

    private void startRelay(ServerConfig config) {
        stopRelay();
        ServerLog.append(this, "BRIDGE", "Starting Minecraft bridge client to "
                + config.bridgeHost + ":" + config.bridgePort);
        bridgeClient = new VCMumbleBridgeClient(config, (status, connected, tracked) -> {
            ServerLog.append(this, "BRIDGE", "status=" + status + ", connected=" + connected
                    + ", tracked=" + tracked);
            bridgeStatus = status;
            trackedPlayers = tracked;
            updateNotification(null);
            publish(true, serverAddress);
        });
        bridgeClient.start();
    }

    private void stopRelay() {
        VCMumbleBridgeClient current = bridgeClient;
        bridgeClient = null;
        if (current != null) current.stop();
        trackedPlayers = 0;
    }

    private void scheduleBackgroundHealthCheck() {
        handler.removeCallbacks(backgroundHealthCheck);
        handler.postDelayed(backgroundHealthCheck, BACKGROUND_HEALTH_INTERVAL_MS);
    }

    private final Runnable backgroundHealthCheck = new Runnable() {
        @Override public void run() {
            if (!ServerRuntimeState.shouldRun(MumbleServerService.this)) return;
            final int port = serverPort;
            Thread probeThread = new Thread(() -> {
                boolean healthy = probeBackgroundTcp(port);
                handler.post(() -> {
                    if (!ServerRuntimeState.shouldRun(MumbleServerService.this)) return;
                    backgroundHealthChecks++;
                    if (healthy) {
                        if (backgroundHealthFailures > 0) {
                            ServerLog.append(MumbleServerService.this, "HEALTH",
                                    "Background TCP health recovered on 127.0.0.1:" + port);
                        }
                        backgroundHealthFailures = 0;
                        updateRuntimeProtectionStatus(true);
                        if (backgroundHealthChecks % 10 == 0) {
                            ServerLog.append(MumbleServerService.this, "HEALTH",
                                    "Background runtime healthy; TCP listener responsive");
                        }
                    } else {
                        backgroundHealthFailures++;
                        updateRuntimeProtectionStatus(false);
                        runtimeProtectionStatus += " • Health warning x" + backgroundHealthFailures;
                        ServerLog.append(MumbleServerService.this, "HEALTH",
                                "Background TCP health check failed (" + backgroundHealthFailures
                                        + ") on 127.0.0.1:" + port);
                    }
                    publish(true, serverAddress);
                    if (ServerRuntimeState.shouldRun(MumbleServerService.this)) {
                        scheduleBackgroundHealthCheck();
                    }
                });
            }, "VCMumble-Background-Health");
            probeThread.setDaemon(true);
            probeThread.start();
        }
    };

    private boolean probeBackgroundTcp(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress("127.0.0.1", port),
                    BACKGROUND_HEALTH_CONNECT_TIMEOUT_MS
            );
            return socket.isConnected();
        } catch (IOException ignored) {
            return false;
        }
    }

    private void publish(boolean running, String message) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_RUNNING, running);
        i.putExtra(EXTRA_MESSAGE, message);
        i.putExtra(EXTRA_BRIDGE, bridgeStatus);
        i.putExtra(EXTRA_TRACKED, trackedPlayers);
        i.putExtra(EXTRA_BACKGROUND, runtimeProtectionStatus);
        sendBroadcast(i);
    }

    private void updateNotification(String override) {
        String text = override;
        if (text == null) {
            text = "Online • " + serverAddress;
            if (NativeServer.isProximityEnabledForUi()) {
                text += " • " + trackedPlayers + " Minecraft";
            }
        }
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stop = new Intent(this, MumbleServerService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("VC Mumble Server")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopAction).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Mumble server",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps VC Mumble Server running in the background");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}
