package com.voicecraft.vcmumbleserver;

import android.content.Context;
import android.content.SharedPreferences;

public final class ServerConfig {
    public static final String PREFS = "vc_mumble_server";

    public final String serverName;
    public final int port;
    public final String password;
    public final int maxUsers;
    public final int voiceRange;
    public final boolean proximityEnabled;
    public final String bridgeHost;
    public final int bridgePort;
    public final String bridgeSecret;

    public ServerConfig(
            String serverName,
            int port,
            String password,
            int maxUsers,
            int voiceRange,
            boolean proximityEnabled,
            String bridgeHost,
            int bridgePort,
            String bridgeSecret
    ) {
        this.serverName = serverName;
        this.port = port;
        this.password = password;
        this.maxUsers = maxUsers;
        this.voiceRange = voiceRange;
        this.proximityEnabled = proximityEnabled;
        this.bridgeHost = bridgeHost;
        this.bridgePort = bridgePort;
        this.bridgeSecret = bridgeSecret;
    }

    public static ServerConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new ServerConfig(
                p.getString("server_name", "Minecraft Voice"),
                p.getInt("port", 64738),
                p.getString("password", ""),
                p.getInt("max_users", 20),
                p.getInt("voice_range", 30),
                p.getBoolean("proximity_enabled", false),
                p.getString("bridge_host", ""),
                p.getInt("bridge_port", 27220),
                SecretStore.load(context)
        );
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("server_name", serverName)
                .putInt("port", port)
                .putString("password", password)
                .putInt("max_users", maxUsers)
                .putInt("voice_range", voiceRange)
                .putBoolean("proximity_enabled", proximityEnabled)
                .putString("bridge_host", bridgeHost)
                .putInt("bridge_port", bridgePort)
                .apply();
        SecretStore.save(context, bridgeSecret);
    }

    public boolean hasUsableBridgeConfig() {
        return bridgeHost != null && !bridgeHost.trim().isEmpty()
                && bridgePort >= 1 && bridgePort <= 65535
                && bridgeSecret != null && !bridgeSecret.isEmpty();
    }
}
