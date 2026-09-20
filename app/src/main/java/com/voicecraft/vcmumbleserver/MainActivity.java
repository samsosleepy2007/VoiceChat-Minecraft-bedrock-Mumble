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
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
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
    private static final int PAGE_HOME = 0;
    private static final int PAGE_LOG = 1;
    private static final int PAGE_SETTINGS = 2;

    private TextView status;
    private TextView address;
    private TextView bridgeStatus;
    private LinearLayout statusCard;
    private TextView portWarpTarget;

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
    private TextView logView;

    private View homePage;
    private View logPage;
    private View settingsPage;
    private Button navHome;
    private Button navLog;
    private Button navSettings;

    private boolean running;
    private int currentPage = PAGE_HOME;

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
        configureSystemBars();
        requestNotificationsIfNeeded();
        setContentView(buildUi());
        fillConfig(ServerConfig.load(this));
        updateState(false, "พร้อมเริ่มเซิร์ฟเวอร์", "ระบบเชื่อมต่อ Minecraft พร้อมใช้งาน", 0);
        showPage(PAGE_HOME);
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(cyberBackground());

        settingsPage = buildSettingsPage();
        homePage = buildHomePage();
        logPage = buildLogPage();

        FrameLayout content = new FrameLayout(this);
        content.addView(homePage, frameMatch());
        content.addView(logPage, frameMatch());
        content.addView(settingsPage, frameMatch());
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        root.addView(buildBottomNav());
        return root;
    }

    private View buildHomePage() {
        LinearLayout root = pageRoot();
        addScreenHeader(
                root,
                "VC MUMBLE // NODE",
                "หน้าหลัก",
                "ศูนย์ควบคุมเซิร์ฟเวอร์เสียง Minecraft Bedrock"
        );

        statusCard = card();
        TextView statusLabel = eyebrow("สถานะเซิร์ฟเวอร์");
        statusCard.addView(statusLabel);

        status = text("● ออฟไลน์", 24, true);
        statusCard.addView(status, marginTop(8));

        address = text("พร้อมเริ่มเซิร์ฟเวอร์", 15, false);
        address.setTextIsSelectable(true);
        statusCard.addView(address, marginTop(8));

        bridgeStatus = text("ระบบเชื่อมต่อ Minecraft พร้อมใช้งาน", 12, false);
        bridgeStatus.setTextColor(c(R.color.cyber_text_secondary));
        statusCard.addView(bridgeStatus, marginTop(8));

        root.addView(statusCard, marginTop(18));

        startStop = primaryButton("เปิดเซิร์ฟเวอร์");
        startStop.setOnClickListener(v -> toggleServer());
        root.addView(startStop, marginTop(14));

        Button quick = secondaryButton("เริ่มด่วนด้วยค่ามาตรฐาน");
        quick.setOnClickListener(v -> {
            serverName.setText("Minecraft Voice");
            port.setText("64738");
            password.setText("");
            maxUsers.setText("20");
            voiceRange.setText("30");
            proximityEnabled.setChecked(false);
            try {
                saveSettings(false);
                if (!running) toggleServer();
            } catch (Exception error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(quick, marginTop(8));

        LinearLayout runtimeCard = card();
        runtimeCard.addView(eyebrow("ระบบ Mumble"));
        String nativeMode = NativeServer.hasMumbleCore()
                ? "ใช้ Mumble Core แบบฝังในแอป รองรับโปรโตคอล Mumble/Mumla มาตรฐาน"
                : "โหมดทดสอบ Transport เท่านั้น รุ่นนี้ยังไม่รองรับโปรโตคอล Mumble เต็มรูปแบบ";
        TextView runtime = text(nativeMode, 13, false);
        runtime.setTextColor(c(R.color.cyber_text_secondary));
        runtimeCard.addView(runtime, marginTop(8));
        root.addView(runtimeCard, marginTop(14));

        LinearLayout publicCard = card();
        publicCard.addView(eyebrow("PUBLIC ACCESS // PORTWARP"));
        TextView publicNote = text(
                "ใช้แอป PortWarp ทางการคู่กับ VC Mumble Server เพื่อเปิดเซิร์ฟเวอร์ออกอินเทอร์เน็ต "
                        + "สร้าง Tunnel แบบ TCP+UDP แล้วชี้ Local Host ไปที่ 127.0.0.1 และใช้ Port เดียวกับ Mumble",
                13,
                false
        );
        publicNote.setTextColor(c(R.color.cyber_text_secondary));
        publicCard.addView(publicNote, marginTop(8));

        portWarpTarget = text(
                "เป้าหมาย PortWarp: 127.0.0.1:" + ServerConfig.load(this).port,
                14,
                true
        );
        portWarpTarget.setTextColor(c(R.color.cyber_neon));
        portWarpTarget.setTextIsSelectable(true);
        publicCard.addView(portWarpTarget, marginTop(10));

        Button playStore = secondaryButton("เปิด PortWarp ใน Google Play");
        playStore.setOnClickListener(v -> openPortWarpPlayStore());
        publicCard.addView(playStore, marginTop(10));
        root.addView(publicCard, marginTop(14));

        TextView footer = text("VC // SECURE VOICE NODE", 10, true);
        footer.setGravity(Gravity.CENTER);
        footer.setTextColor(c(R.color.cyber_border));
        root.addView(footer, marginTop(20));

        return wrapScroll(root);
    }

    private View buildLogPage() {
        LinearLayout root = pageRoot();
        addScreenHeader(
                root,
                "LOG // TERMINAL",
                "ระบบบันทึก",
                "บันทึกการทำงานของระบบ — เนื้อหา Log คงภาษาอังกฤษ"
        );

        LinearLayout terminal = card();
        terminal.setBackground(rounded(
                c(R.color.cyber_log_bg),
                c(R.color.cyber_neon_dim),
                14,
                1
        ));

        TextView terminalTitle = text("SYSTEM OUTPUT", 11, true);
        terminalTitle.setTextColor(c(R.color.cyber_neon));
        terminal.addView(terminalTitle);

        logView = text("", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(c(R.color.cyber_log_text));
        logView.setTextIsSelectable(true);
        logView.setMinLines(20);
        logView.setPadding(0, dp(12), 0, dp(12));
        terminal.addView(logView, marginTop(4));
        root.addView(terminal, marginTop(18));

        Button copyLog = secondaryButton("คัดลอก Log");
        copyLog.setOnClickListener(v -> copyLogToClipboard());
        root.addView(copyLog, marginTop(10));

        Button downloadLog = secondaryButton("บันทึก log.txt");
        downloadLog.setOnClickListener(v -> exportLog());
        root.addView(downloadLog, marginTop(8));

        Button clearLog = dangerButton("ล้าง Log");
        clearLog.setOnClickListener(v -> {
            ServerLog.clear(this);
            refreshLogView();
            Toast.makeText(this, "ล้าง Log แล้ว", Toast.LENGTH_SHORT).show();
        });
        root.addView(clearLog, marginTop(8));

        return wrapScroll(root);
    }

    private View buildSettingsPage() {
        LinearLayout root = pageRoot();
        addScreenHeader(
                root,
                "CONFIG // SETTINGS",
                "ตั้งค่า",
                "ตั้งค่าเซิร์ฟเวอร์และระบบ Minecraft Proximity"
        );

        LinearLayout serverCard = card();
        serverCard.addView(eyebrow("ตั้งค่า Mumble Server"));

        serverName = field("ชื่อเซิร์ฟเวอร์", InputType.TYPE_CLASS_TEXT);
        port = field("พอร์ต", InputType.TYPE_CLASS_NUMBER);
        password = field(
                "รหัสผ่านเซิร์ฟเวอร์ (ไม่บังคับ)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        maxUsers = field("จำนวนผู้เล่นสูงสุด", InputType.TYPE_CLASS_NUMBER);

        addLabeledField(serverCard, "ชื่อเซิร์ฟเวอร์", serverName);
        addLabeledField(serverCard, "Port", port);
        addLabeledField(serverCard, "รหัสผ่าน", password);
        addLabeledField(serverCard, "ผู้เล่นสูงสุด", maxUsers);
        root.addView(serverCard, marginTop(18));

        LinearLayout proximityCard = card();
        proximityCard.addView(eyebrow("Minecraft Proximity"));

        voiceRange = field("ระยะเสียง (บล็อก)", InputType.TYPE_CLASS_NUMBER);
        addLabeledField(proximityCard, "ระยะเสียง", voiceRange);

        proximityEnabled = new CheckBox(this);
        proximityEnabled.setText("เปิดใช้งาน Minecraft Proximity Routing");
        proximityEnabled.setTextSize(14);
        proximityEnabled.setTextColor(c(R.color.cyber_text));
        proximityEnabled.setButtonTintList(android.content.res.ColorStateList.valueOf(c(R.color.cyber_neon)));
        proximityEnabled.setOnCheckedChangeListener((buttonView, checked) -> updateBridgeFieldVisibility());
        proximityCard.addView(proximityEnabled, marginTop(10));

        TextView proximityNote = text(
                "VC Mumble Endstone จะส่งชื่อผู้ใช้ Mumble, Dimension และระยะเสียงเข้ามา "
                        + "หากชื่อ Minecraft กับ Mumble ไม่ตรงกันให้ใช้ /vcmumble pair <name>",
                12,
                false
        );
        proximityNote.setTextColor(c(R.color.cyber_text_secondary));
        proximityCard.addView(proximityNote, marginTop(6));

        bridgeHost = field(
                "Host ของ Minecraft Server เช่น sv5.mcsv.me",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
        );
        bridgePort = field("VC Mumble Bridge Port", InputType.TYPE_CLASS_NUMBER);
        bridgeSecret = field(
                "Bridge Secret",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        addLabeledField(proximityCard, "Minecraft Server Host", bridgeHost);
        addLabeledField(proximityCard, "Bridge Port", bridgePort);
        addLabeledField(proximityCard, "Bridge Secret", bridgeSecret);

        TextView secretNote = text(
                "Bridge Secret จะถูกเข้ารหัสด้วย Android Keystore ก่อนบันทึกลงเครื่อง",
                11,
                false
        );
        secretNote.setTextColor(c(R.color.cyber_text_secondary));
        proximityCard.addView(secretNote, marginTop(6));
        root.addView(proximityCard, marginTop(14));

        Button save = primaryButton("บันทึกการตั้งค่า");
        save.setOnClickListener(v -> {
            try {
                saveSettings(true);
            } catch (Exception error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(save, marginTop(14));

        TextView stopHint = text(
                "หากเซิร์ฟเวอร์กำลังทำงาน ต้องปิดเซิร์ฟเวอร์ก่อนจึงจะแก้ไขค่าหลักได้",
                11,
                false
        );
        stopHint.setTextColor(c(R.color.cyber_text_secondary));
        root.addView(stopHint, marginTop(8));

        return wrapScroll(root);
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(10), dp(8), dp(10), dp(10));
        nav.setBackground(rounded(
                c(R.color.cyber_nav),
                c(R.color.cyber_border),
                0,
                0
        ));

        navHome = navButton("หน้าหลัก");
        navLog = navButton("LOG");
        navSettings = navButton("ตั้งค่า");

        navHome.setOnClickListener(v -> showPage(PAGE_HOME));
        navLog.setOnClickListener(v -> showPage(PAGE_LOG));
        navSettings.setOnClickListener(v -> showPage(PAGE_SETTINGS));

        LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(0, dp(52), 1f);
        item.setMarginStart(dp(4));
        item.setMarginEnd(dp(4));
        nav.addView(navHome, item);
        nav.addView(navLog, item);
        nav.addView(navSettings, item);
        return nav;
    }

    private void showPage(int page) {
        currentPage = page;
        homePage.setVisibility(page == PAGE_HOME ? View.VISIBLE : View.GONE);
        logPage.setVisibility(page == PAGE_LOG ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(page == PAGE_SETTINGS ? View.VISIBLE : View.GONE);

        styleNavButton(navHome, page == PAGE_HOME);
        styleNavButton(navLog, page == PAGE_LOG);
        styleNavButton(navSettings, page == PAGE_SETTINGS);

        if (page == PAGE_LOG) refreshLogView();
        if (page == PAGE_HOME) {
            refreshServerStateFromTcp();
            updatePortWarpTarget();
        }
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
            updatePortWarpTarget();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            ServerLog.append(this, "ERROR", "Unable to start: " + e.getClass().getSimpleName()
                    + ": " + String.valueOf(e.getMessage()));
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, MumbleServerService.class).setAction(MumbleServerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void saveSettings(boolean showToast) throws IOException {
        if (running) {
            throw new IllegalStateException("กรุณาปิดเซิร์ฟเวอร์ก่อนแก้ไขการตั้งค่า");
        }
        ServerConfig cfg = readConfig();
        cfg.save(this);
        MumbleConfigWriter.write(this, cfg);
        updatePortWarpTarget();
        ServerLog.append(this, "UI", "Server settings saved; port=" + cfg.port
                + ", maxUsers=" + cfg.maxUsers + ", proximity=" + cfg.proximityEnabled);
        if (showToast) Toast.makeText(this, "บันทึกการตั้งค่าแล้ว", Toast.LENGTH_SHORT).show();
    }

    private ServerConfig readConfig() {
        String name = serverName.getText().toString().trim();
        if (name.isEmpty()) name = "Minecraft Voice";
        int p = parseInt(port, "Port", 1, 65535);
        int users = parseInt(maxUsers, "จำนวนผู้เล่นสูงสุด", 1, 1000);
        int range = parseInt(voiceRange, "ระยะเสียง", 1, 1000);
        String bridgeHostValue = bridgeHost.getText().toString().trim();
        int bridgePortValue = parseInt(bridgePort, "Bridge Port", 1, 65535);
        String secret = bridgeSecret.getText().toString();
        boolean proximity = proximityEnabled.isChecked();

        if (proximity) {
            if (bridgeHostValue.isEmpty()) {
                throw new IllegalArgumentException("กรุณาระบุ Minecraft Bridge Host");
            }
            if (secret.isEmpty()) {
                throw new IllegalArgumentException("กรุณาระบุ Bridge Secret เมื่อเปิด Proximity");
            }
        }

        return new ServerConfig(
                name,
                p,
                password.getText().toString(),
                users,
                range,
                proximity,
                bridgeHostValue,
                bridgePortValue,
                secret
        );
    }

    private int parseInt(EditText input, String label, int min, int max) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " ต้องอยู่ระหว่าง " + min + "–" + max);
        }
    }

    private void fillConfig(ServerConfig config) {
        serverName.setText(config.serverName);
        port.setText(String.valueOf(config.port));
        password.setText(config.password);
        maxUsers.setText(String.valueOf(config.maxUsers));
        voiceRange.setText(String.valueOf(config.voiceRange));
        proximityEnabled.setChecked(config.proximityEnabled);
        bridgeHost.setText(config.bridgeHost);
        bridgePort.setText(String.valueOf(config.bridgePort));
        bridgeSecret.setText(config.bridgeSecret);
        updateBridgeFieldVisibility();
        updatePortWarpTarget();
    }

    private void updateState(boolean isRunning, String message, String bridge, int tracked) {
        running = isRunning;
        boolean starting = isRunning && message != null && message.startsWith("Starting");

        if (starting) {
            status.setText("● กำลังเริ่ม");
            status.setTextColor(c(R.color.cyber_warning));
            statusCard.setBackground(rounded(
                    c(R.color.cyber_surface),
                    c(R.color.cyber_warning),
                    16,
                    1
            ));
        } else if (isRunning) {
            status.setText("● ออนไลน์");
            status.setTextColor(c(R.color.cyber_success));
            statusCard.setBackground(rounded(
                    c(R.color.cyber_surface),
                    c(R.color.cyber_success),
                    16,
                    1
            ));
        } else {
            status.setText("● ออฟไลน์");
            status.setTextColor(c(R.color.cyber_danger));
            statusCard.setBackground(rounded(
                    c(R.color.cyber_surface),
                    c(R.color.cyber_danger),
                    16,
                    1
            ));
        }

        String displayMessage = message == null ? "" : message;
        if ("Ready".equals(displayMessage)) displayMessage = "พร้อมเริ่มเซิร์ฟเวอร์";
        address.setText(displayMessage);

        String bridgeText = bridge == null ? "" : bridge;
        if ("Bridge idle".equals(bridgeText)) bridgeText = "ระบบเชื่อมต่อ Minecraft พร้อมใช้งาน";
        if (tracked > 0) {
            bridgeText += " • ติดตาม " + tracked + " ผู้เล่น";
        }
        bridgeStatus.setText(bridgeText);

        startStop.setText(isRunning ? "ปิดเซิร์ฟเวอร์" : "เปิดเซิร์ฟเวอร์");
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
                            "ตรวจพบ Mumble Process",
                            0
                    );
                } else if (!running) {
                    updateState(false, "พร้อมเริ่มเซิร์ฟเวอร์", "ระบบเชื่อมต่อ Minecraft พร้อมใช้งาน", 0);
                }
            });
        }, "VCMumble-UI-State-Probe").start();
    }

    private void updatePortWarpTarget() {
        if (portWarpTarget != null) {
            portWarpTarget.setText(
                    "เป้าหมาย PortWarp: 127.0.0.1:" + ServerConfig.load(this).port
            );
        }
    }

    private void openPortWarpPlayStore() {
        final String packageName = "com.ribeirosoftware.portwarp";
        try {
            Intent market = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + packageName)
            );
            market.setPackage("com.android.vending");
            startActivity(market);
        } catch (Exception ignored) {
            openUrl(
                    "https://play.google.com/store/apps/details?id=" + packageName,
                    "PortWarp Google Play"
            );
        }
    }

    private void openUrl(String url, String label) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(label, url));
            Toast.makeText(this, "ไม่พบ Browser จึงคัดลอกลิงก์ไว้แล้ว", Toast.LENGTH_LONG).show();
        }
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
        Toast.makeText(this, "คัดลอก Log แล้ว", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "บันทึก log.txt แล้ว", Toast.LENGTH_SHORT).show();
        } catch (IOException error) {
            ServerLog.append(this, "ERROR", "Log export failed: " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage()));
            refreshLogView();
            Toast.makeText(this, "บันทึก log.txt ไม่สำเร็จ: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void configureSystemBars() {
        boolean darkMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        getWindow().setStatusBarColor(c(R.color.cyber_bg));
        getWindow().setNavigationBarColor(c(R.color.cyber_nav));

        int flags = 0;
        if (!darkMode && Build.VERSION.SDK_INT >= 23) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (!darkMode && Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void addScreenHeader(
            LinearLayout root,
            String code,
            String titleText,
            String subtitle
    ) {
        TextView codeView = text(code, 12, true);
        codeView.setTextColor(c(R.color.cyber_neon));
        root.addView(codeView);

        TextView title = text(titleText, 26, true);
        root.addView(title, marginTop(4));

        TextView sub = text(subtitle, 13, false);
        sub.setTextColor(c(R.color.cyber_text_secondary));
        root.addView(sub, marginTop(4));

        View line = new View(this);
        line.setBackgroundColor(c(R.color.cyber_neon));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(74), dp(2));
        lp.topMargin = dp(12);
        root.addView(line, lp);
    }

    private LinearLayout pageRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        root.setBackgroundColor(Color.TRANSPARENT);
        return root;
    }

    private ScrollView wrapScroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(child);
        return scroll;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(rounded(
                c(R.color.cyber_surface),
                c(R.color.cyber_border),
                16,
                1
        ));
        return layout;
    }

    private TextView eyebrow(String value) {
        TextView view = text(value, 11, true);
        view.setTextColor(c(R.color.cyber_neon));
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(c(R.color.cyber_text));
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setLineSpacing(0f, 1.08f);
        return view;
    }

    private EditText field(String hint, int type) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(type);
        input.setTextSize(15);
        input.setTextColor(c(R.color.cyber_text));
        input.setHintTextColor(c(R.color.cyber_text_secondary));
        input.setPadding(dp(14), dp(11), dp(14), dp(11));
        input.setBackground(rounded(
                c(R.color.cyber_panel),
                c(R.color.cyber_neon_dim),
                10,
                1
        ));
        return input;
    }

    private void addLabeledField(LinearLayout parent, String label, EditText input) {
        TextView caption = text(label, 12, true);
        caption.setTextColor(c(R.color.cyber_text_secondary));
        parent.addView(caption, marginTop(12));
        parent.addView(input, marginTop(5));
    }

    private Button primaryButton(String label) {
        Button button = buttonBase(label);
        button.setTextColor(Color.rgb(0, 26, 38));
        button.setBackground(rounded(
                c(R.color.cyber_neon),
                c(R.color.cyber_neon_soft),
                12,
                1
        ));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = buttonBase(label);
        button.setTextColor(c(R.color.cyber_neon));
        button.setBackground(rounded(
                c(R.color.cyber_surface_alt),
                c(R.color.cyber_neon),
                12,
                1
        ));
        return button;
    }

    private Button dangerButton(String label) {
        Button button = buttonBase(label);
        button.setTextColor(c(R.color.cyber_danger));
        button.setBackground(rounded(
                c(R.color.cyber_surface),
                c(R.color.cyber_danger),
                12,
                1
        ));
        return button;
    }

    private Button navButton(String label) {
        Button button = buttonBase(label);
        button.setTextSize(13);
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private Button buttonBase(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(button.getTypeface(), Typeface.BOLD);
        button.setMinHeight(dp(50));
        button.setStateListAnimator(null);
        return button;
    }

    private void styleNavButton(Button button, boolean selected) {
        button.setTextColor(selected ? c(R.color.cyber_neon) : c(R.color.cyber_text_secondary));
        button.setBackground(rounded(
                selected ? c(R.color.cyber_nav_selected) : c(R.color.cyber_nav),
                selected ? c(R.color.cyber_neon) : c(R.color.cyber_nav),
                10,
                selected ? 1 : 0
        ));
    }

    private GradientDrawable cyberBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{c(R.color.cyber_bg), c(R.color.cyber_panel)}
        );
        drawable.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return drawable;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams marginTop(int valueDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(valueDp);
        return params;
    }

    private FrameLayout.LayoutParams frameMatch() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
    }

    private int c(int resourceId) {
        if (Build.VERSION.SDK_INT >= 23) return getColor(resourceId);
        return getResources().getColor(resourceId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
