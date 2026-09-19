package com.voicecraft.vcmumbleserver;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class MainActivity extends Activity {
    private static final int REQUEST_EXPORT_LOG = 4201;

    private TextView status;
    private TextView address;
    private TextView bridgeStatus;
    private EditText serverName;
    private EditText port;
    private EditText password;
    private EditText maxUsers;
    private EditText voiceRange;
    private CheckBox proximityEnabled;
    private EditText bridgeHost;
    private EditText bridgePort;
    private EditText bridgeSecret;
    private Button startStop;
    private Button playitProbeButton;
    private TextView playitProbeStatus;
    private TextView logView;
    private boolean running;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!MumbleServerService.ACTION_STATE.equals(intent.getAction())) return;
            updateState(
                    intent.getBooleanExtra(MumbleServerService.EXTRA_RUNNING, false),
                    intent.getStringExtra(MumbleServerService.EXTRA_MESSAGE),
                    intent.getStringExtra(MumbleServerService.EXTRA_BRIDGE),
                    intent.getIntExtra(MumbleServerService.EXTRA_TRACKED, 0)
            );
            refreshLogView();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationsIfNeeded();
        setContentView(buildUi());
        fillConfig(ServerConfig.load(this));
        // MumbleServerService runs in the dedicated :mumble process. NativeServer
        // static state is therefore intentionally not used by the UI process.
        updateState(false, "Ready", "Bridge idle", 0);
    }

    @Override protected void onStart() {
        super.onStart();
        refreshLogView();
        IntentFilter filter = new IntentFilter(MumbleServerService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        refreshServerStateFromTcp();
    }

    @Override protected void onStop() {
        unregisterReceiver(stateReceiver);
        super.onStop();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(245, 249, 255));

        root.addView(text("VC Mumble Server", 28, true));
        TextView sub = text("One-tap Mumble server for Minecraft Bedrock", 14, false);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub, marginTop(6));

        LinearLayout card = card();
        status = text("● OFFLINE", 20, true);
        address = text("Ready", 15, false);
        bridgeStatus = text("Bridge idle", 13, false);
        bridgeStatus.setTextColor(Color.GRAY);
        card.addView(status);
        card.addView(address, marginTop(6));
        card.addView(bridgeStatus, marginTop(6));
        root.addView(card, marginTop(20));

        root.addView(section("Mumble Server"), marginTop(18));
        serverName = field("Server name", InputType.TYPE_CLASS_TEXT);
        port = field("Port", InputType.TYPE_CLASS_NUMBER);
        password = field("Server password (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        maxUsers = field("Max players", InputType.TYPE_CLASS_NUMBER);
        root.addView(serverName, marginTop(8));
        root.addView(port, marginTop(10));
        root.addView(password, marginTop(10));
        root.addView(maxUsers, marginTop(10));

        root.addView(section("Minecraft Proximity"), marginTop(20));
        voiceRange = field("Voice range (blocks)", InputType.TYPE_CLASS_NUMBER);
        root.addView(voiceRange, marginTop(8));

        proximityEnabled = new CheckBox(this);
        proximityEnabled.setText("Enable Minecraft proximity routing");
        proximityEnabled.setTextSize(15);
        proximityEnabled.setOnCheckedChangeListener((buttonView, checked) -> updateBridgeFieldVisibility());
        root.addView(proximityEnabled, marginTop(10));

        TextView proximityNote = text(
                "VC Mumble Endstone supplies the Mumble username, dimension and range. Use /vcmumble pair <name> in Minecraft when your Mumble username is different.",
                12,
                false
        );
        proximityNote.setTextColor(Color.GRAY);
        root.addView(proximityNote, marginTop(4));

        bridgeHost = field("Minecraft server host (example: sv5.mcsv.me)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        bridgePort = field("VC Mumble bridge port", InputType.TYPE_CLASS_NUMBER);
        bridgeSecret = field("Bridge secret", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(bridgeHost, marginTop(10));
        root.addView(bridgePort, marginTop(10));
        root.addView(bridgeSecret, marginTop(10));

        TextView secretNote = text("Bridge secret is encrypted with Android Keystore before it is saved on this phone.", 11, false);
        secretNote.setTextColor(Color.GRAY);
        root.addView(secretNote, marginTop(4));

        startStop = button("START SERVER");
        startStop.setOnClickListener(v -> toggleServer());
        root.addView(startStop, marginTop(20));

        Button quick = button("QUICK START");
        quick.setOnClickListener(v -> {
            serverName.setText("Minecraft Voice");
            port.setText("64738");
            password.setText("");
            maxUsers.setText("20");
            voiceRange.setText("30");
            proximityEnabled.setChecked(false);
            if (!running) toggleServer();
        });
        root.addView(quick, marginTop(10));

        String nativeMode = NativeServer.hasMumbleCore()
                ? "Embedded Mumble core build: stock Mumble/Mumla protocol path enabled."
                : "Transport smoke-test build: TCP/UDP lifecycle only; this build does not speak the Mumble protocol.";
        TextView note = text(nativeMode, 12, false);
        note.setTextColor(Color.GRAY);
        root.addView(note, marginTop(18));

        root.addView(section("Public Access (Playit Experimental)"), marginTop(22));
        TextView playitNote = text(
                BuildConfig.VC_EMBEDDED_PLAYIT
                        ? "This APK contains playit-agent binaries compiled from the pinned BSD-2-Clause source. "
                            + "This step only verifies that Android can execute the packaged CLI and daemon."
                        : "Embedded playit is not included in this build.",
                12,
                false
        );
        playitNote.setTextColor(Color.GRAY);
        root.addView(playitNote, marginTop(6));

        playitProbeStatus = text(
                BuildConfig.VC_EMBEDDED_PLAYIT
                        ? "Embedded playit: not tested on this device"
                        : "Embedded playit: unavailable",
                13,
                false
        );
        root.addView(playitProbeStatus, marginTop(8));

        playitProbeButton = button("TEST EMBEDDED PLAYIT");
        playitProbeButton.setEnabled(BuildConfig.VC_EMBEDDED_PLAYIT);
        playitProbeButton.setOnClickListener(v -> runEmbeddedPlayitProbe());
        root.addView(playitProbeButton, marginTop(8));

        root.addView(section("Server Log"), marginTop(22));
        logView = text("", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMinLines(8);
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logView.setBackgroundColor(Color.WHITE);
        root.addView(logView, marginTop(8));

        Button copyLog = button("Copy Log");
        copyLog.setOnClickListener(v -> copyLogToClipboard());
        root.addView(copyLog, marginTop(8));

        Button downloadLog = button("Download log.txt");
        downloadLog.setOnClickListener(v -> exportLog());
        root.addView(downloadLog, marginTop(8));

        Button clearLog = button("Clear Log");
        clearLog.setOnClickListener(v -> {
            ServerLog.clear(this);
            refreshLogView();
            Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show();
        });
        root.addView(clearLog, marginTop(8));

        refreshLogView();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void toggleServer() {
        if (running) {
            ServerLog.append(this, "UI", "Stop server button pressed");
            startService(new Intent(this, MumbleServerService.class).setAction(MumbleServerService.ACTION_STOP));
            return;
        }
        ServerConfig cfg;
        try {
            cfg = readConfig();
            cfg.save(this);
            // Core builds are launched by QtService, which may enter native main()
            // during service creation. Write the ini before starting the service
            // so Mumble always sees a complete configuration on first boot.
            java.io.File ini = MumbleConfigWriter.write(this, cfg);
            ServerLog.append(
                    this,
                    "UI",
                    "Start server; address=" + NetworkUtil.bestLanIpv4() + ":" + cfg.port
                            + ", ini=" + ini.getAbsolutePath()
                            + ", maxUsers=" + cfg.maxUsers
                            + ", proximity=" + cfg.proximityEnabled
            );
            refreshLogView();
        } catch (IllegalArgumentException | IllegalStateException | java.io.IOException e) {
            ServerLog.append(this, "ERROR", "Unable to start: " + e.getClass().getSimpleName()
                    + ": " + String.valueOf(e.getMessage()));
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, MumbleServerService.class).setAction(MumbleServerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private ServerConfig readConfig() {
        String name = serverName.getText().toString().trim();
        if (name.isEmpty()) name = "Minecraft Voice";
        int p = parseInt(port, "Port", 1, 65535);
        int users = parseInt(maxUsers, "Max players", 1, 1000);
        int range = parseInt(voiceRange, "Voice range", 1, 1000);
        String bridgeHostValue = bridgeHost.getText().toString().trim();
        int bridgePortValue = parseInt(bridgePort, "Bridge port", 1, 65535);
        String secret = bridgeSecret.getText().toString();
        boolean proximity = proximityEnabled.isChecked();
        if (proximity) {
            if (bridgeHostValue.isEmpty()) throw new IllegalArgumentException("Minecraft bridge host is required");
            if (secret.isEmpty()) throw new IllegalArgumentException("Bridge secret is required for proximity mode");
        }
        return new ServerConfig(name, p, password.getText().toString(), users, range,
                proximity, bridgeHostValue, bridgePortValue, secret);
    }

    private int parseInt(EditText input, String label, int min, int max) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be " + min + "–" + max);
        }
    }

    private void fillConfig(ServerConfig c) {
        serverName.setText(c.serverName);
        port.setText(String.valueOf(c.port));
        password.setText(c.password);
        maxUsers.setText(String.valueOf(c.maxUsers));
        voiceRange.setText(String.valueOf(c.voiceRange));
        proximityEnabled.setChecked(c.proximityEnabled);
        bridgeHost.setText(c.bridgeHost);
        bridgePort.setText(String.valueOf(c.bridgePort));
        bridgeSecret.setText(c.bridgeSecret);
        updateBridgeFieldVisibility();
    }

    private void updateState(boolean isRunning, String message, String bridge, int tracked) {
        running = isRunning;
        boolean starting = isRunning && message != null && message.startsWith("Starting");
        if (starting) {
            status.setText("● STARTING");
            status.setTextColor(Color.rgb(180, 120, 20));
        } else {
            status.setText(isRunning ? "● ONLINE" : "● OFFLINE");
            status.setTextColor(isRunning ? Color.rgb(25, 135, 84) : Color.rgb(180, 45, 45));
        }
        address.setText(message == null ? "" : message);
        String bridgeText = bridge == null ? "" : bridge;
        if (tracked > 0) bridgeText += " • " + tracked + " Minecraft player" + (tracked == 1 ? "" : "s");
        bridgeStatus.setText(bridgeText);
        startStop.setText(isRunning ? "STOP SERVER" : "START SERVER");
        setFieldsEnabled(!isRunning);
    }

    private void setFieldsEnabled(boolean enabled) {
        serverName.setEnabled(enabled);
        port.setEnabled(enabled);
        password.setEnabled(enabled);
        maxUsers.setEnabled(enabled);
        voiceRange.setEnabled(enabled);
        proximityEnabled.setEnabled(enabled && NativeServer.hasMumbleCore());
        bridgeHost.setEnabled(enabled && proximityEnabled.isChecked());
        bridgePort.setEnabled(enabled && proximityEnabled.isChecked());
        bridgeSecret.setEnabled(enabled && proximityEnabled.isChecked());
    }

    private void updateBridgeFieldVisibility() {
        boolean enabled = !running && proximityEnabled.isChecked() && NativeServer.hasMumbleCore();
        bridgeHost.setEnabled(enabled);
        bridgePort.setEnabled(enabled);
        bridgeSecret.setEnabled(enabled);
    }

    private String currentAddress() {
        ServerConfig c = ServerConfig.load(this);
        return NetworkUtil.bestLanIpv4() + ":" + c.port;
    }

    private void refreshServerStateFromTcp() {
        final ServerConfig config = ServerConfig.load(this);
        final int probePort = config.port;
        new Thread(() -> {
            boolean open = false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", probePort), 300);
                open = socket.isConnected();
            } catch (IOException ignored) {
            }

            final boolean serverOpen = open;
            runOnUiThread(() -> {
                if (serverOpen) {
                    updateState(
                            true,
                            NetworkUtil.bestLanIpv4() + ":" + probePort,
                            "Mumble process detected",
                            0
                    );
                } else if (!running) {
                    updateState(false, "Ready", "Bridge idle", 0);
                }
            });
        }, "VCMumble-UI-State-Probe").start();
    }

    private void runEmbeddedPlayitProbe() {
        if (!BuildConfig.VC_EMBEDDED_PLAYIT) {
            Toast.makeText(this, "This APK does not include embedded playit", Toast.LENGTH_LONG).show();
            return;
        }

        playitProbeButton.setEnabled(false);
        playitProbeStatus.setText("Embedded playit: testing…");
        ServerLog.append(this, "PLAYIT", "User started packaged executable probe");
        refreshLogView();

        PlayitEmbeddedProbe.run(this, (success, message) -> {
            playitProbeButton.setEnabled(true);
            playitProbeStatus.setText(
                    success ? "Embedded playit: EXECUTION PASSED" : "Embedded playit: EXECUTION BLOCKED"
            );
            Toast.makeText(this, message, success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            refreshLogView();
        });
    }

    private void refreshLogView() {
        if (logView != null) {
            String value = ServerLog.read(this);
            logView.setText(value.isEmpty() ? "No log entries yet." : value);
        }
    }

    private void copyLogToClipboard() {
        String value = ServerLog.readAll(this);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("VC Mumble Server log", value));
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    private void exportLog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "log.txt");
        startActivityForResult(intent, REQUEST_EXPORT_LOG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_LOG || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }

        try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "w")) {
            if (output == null) throw new IOException("Unable to open selected destination");
            output.write(ServerLog.readAllBytes(this));
            output.flush();
            Toast.makeText(this, "log.txt saved", Toast.LENGTH_SHORT).show();
        } catch (IOException error) {
            ServerLog.append(this, "ERROR", "Log export failed: " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage()));
            refreshLogView();
            Toast.makeText(this, "Unable to save log.txt: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(Color.rgb(25, 40, 65));
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private TextView section(String value) {
        TextView v = text(value, 16, true);
        v.setTextColor(Color.rgb(45, 95, 165));
        return v;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTextSize(16);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackgroundColor(Color.WHITE);
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinHeight(dp(52));
        return b;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(16), dp(18), dp(16));
        l.setBackgroundColor(Color.WHITE);
        return l;
    }

    private LinearLayout.LayoutParams marginTop(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.topMargin = dp(dp);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
