package com.voicecraft.vcmumbleserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public final class MumbleServerService extends Service {
    public static final String ACTION_START = "com.voicecraft.vcmumbleserver.START";
    public static final String ACTION_STOP = "com.voicecraft.vcmumbleserver.STOP";
    public static final String ACTION_STATE = "com.voicecraft.vcmumbleserver.STATE";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_BRIDGE = "bridge";
    public static final String EXTRA_TRACKED = "tracked";
    private static final int NOTIFICATION_ID = 4107;
    private static final String CHANNEL_ID = "vc_mumble_server";

    private VCMumbleBridgeClient bridgeClient;
    private String serverAddress = "";
    private String bridgeStatus = "Bridge disabled";
    private int trackedPlayers;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRelay();
            NativeServer.setProximityEnabled(false);
            NativeServer.clearPlayerStates();
            NativeServer.stop();
            publish(false, "Stopped");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        ServerConfig config = ServerConfig.load(this);
        Notification notification = buildNotification("Starting on port " + config.port + "…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (!NativeServer.isRunning()) {
            final java.io.File ini;
            try {
                ini = MumbleConfigWriter.write(this, config);
            } catch (java.io.IOException error) {
                publish(false, "Could not write Mumble config: " + error.getMessage());
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }

            int result = NativeServer.start(ini.getAbsolutePath(), config.port, getApplicationInfo().nativeLibraryDir);
            if (result != 0) {
                String error = NativeServer.lastError();
                publish(false, error.isEmpty() ? "Native server failed to start" : error);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        NativeServer.setProximityStaleTimeoutMs(15000L);
        // Test branch: allow the standalone Endstone bridge to be exercised even
        // without the Mumble core. Player state is tracked, but it does not route audio.
        boolean proximityActive = config.proximityEnabled;
        NativeServer.clearPlayerStates();
        NativeServer.setProximityEnabled(proximityActive);

        serverAddress = NetworkUtil.bestLanIpv4() + ":" + config.port;
        if (proximityActive) {
            if (config.hasUsableBridgeConfig()) {
                startRelay(config);
            } else {
                bridgeStatus = "Bridge config missing • proximity waiting";
                updateNotification();
            }
        } else {
            bridgeStatus = "Transport smoke-test";
        }

        updateNotification();
        publish(true, serverAddress);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRelay();
        NativeServer.setProximityEnabled(false);
        NativeServer.clearPlayerStates();
        NativeServer.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startRelay(ServerConfig config) {
        stopRelay();
        bridgeClient = new VCMumbleBridgeClient(config, (status, connected, tracked) -> {
            bridgeStatus = status;
            trackedPlayers = tracked;
            updateNotification();
            publish(NativeServer.isRunning(), serverAddress);
        });
        bridgeClient.start();
    }

    private void stopRelay() {
        VCMumbleBridgeClient current = bridgeClient;
        bridgeClient = null;
        if (current != null) current.stop();
        trackedPlayers = 0;
    }

    private void publish(boolean running, String message) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_RUNNING, running);
        i.putExtra(EXTRA_MESSAGE, message);
        i.putExtra(EXTRA_BRIDGE, bridgeStatus);
        i.putExtra(EXTRA_TRACKED, trackedPlayers);
        sendBroadcast(i);
    }

    private void updateNotification() {
        if (!NativeServer.isRunning()) return;
        String text = "Online • " + serverAddress;
        if (NativeServer.isProximityEnabledForUi()) {
            text += " • " + trackedPlayers + " Minecraft";
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
