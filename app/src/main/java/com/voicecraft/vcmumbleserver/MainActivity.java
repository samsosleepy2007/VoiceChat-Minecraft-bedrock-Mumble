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
import android.graphics.Typeface;
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

    private CyberTheme theme;

    private TextView status;
    private TextView address;
    private TextView bridgeStatus;
    private TextView homeSummary;
    private TextView portWarpTarget;
    private TextView headerPageName;

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

    private FrameLayout pageHost;
    private View homePage;
    private View logPage;
    private View settingsPage;
    private Button navHome;
    private Button navLog;
    private Button navSettings;

    private boolean running;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
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
        theme = CyberTheme.from(this);
        theme.applyWindow(this);
        requestNotificationsIfNeeded();

        setContentView(buildUi());
        fillConfig(ServerConfig.load(this));
        updateState(false, "Ready", "Bridge idle", 0);
        showPage(PAGE_HOME);
    }

    @Override
    protected void onStart() {
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

    @Override
    protected void onStop() {
        unregisterReceiver(stateReceiver);
        super.onStop();
    }

    private View buildUi() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(theme.background);

        shell.addView(buildHeader());

        pageHost = new FrameLayout(this);
        LinearLayout.LayoutParams pageHostParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        shell.addView(pageHost, pageHostParams);

        homePage = buildHomePage();
        logPage = buildLogPage();
        settingsPage = buildSettingsPage();

        FrameLayout.LayoutParams match = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        pageHost.addView(homePage, match);
        pageHost.addView(logPage, match);
        pageHost.addView(settingsPage, match);

        shell.addView(buildBottomNavigation());
        return shell;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(14), dp(18), dp(12));
        header.setBackgroundColor(theme.surface);

        TextView brand = text("VC MUMBLE // NODE", 22, true);
        brand.setTextColor(theme.accent);
        brand.setLetterSpacing(0.08f);
        theme.glowTitle(brand);
        header.addView(brand);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView subtitle = text("Mumble Server สำหรับ Minecraft Bedrock", 12, false);
        subtitle.setTextColor(theme.textSecondary);
        row.addView(subtitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        headerPageName = text("หน้าหลัก", 12, true);
        headerPageName.setTextColor(theme.accent);
        row.addView(headerPageName);
        header.addView(row, marginTop(4));

        TextView line = text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", 9, false);
        line.setTextColor(theme.border);
        header.addView(line, marginTop(3));
        return header;
    }

    private View buildHomePage() {
        LinearLayout root = pageContainer();

        TextView eyebrow = text("SYSTEM // OVERVIEW", 11, true);
        eyebrow.setTextColor(theme.accent);
        eyebrow.setLetterSpacing(0.12f);
        root.addView(eyebrow);

        TextView title = text("ศูนย์ควบคุมเซิร์ฟเวอร์", 25, true);
        root.addView(title, marginTop(4));

        TextView desc = text(
                "เปิด Mumble Server บนมือถือและดูสถานะระบบจากหน้าหลัก",
                13,
                false
        );
        desc.setTextColor(theme.textSecondary);
        root.addView(desc, marginTop(4));

        LinearLayout statusCard = card(true);
        TextView nodeLabel = text("NODE // STATUS", 11, true);
        nodeLabel.setTextColor(theme.accent);
        nodeLabel.setLetterSpacing(0.12f);
        statusCard.addView(nodeLabel);

        status = text("● ออฟไลน์", 27, true);
        status.setTextColor(theme.danger);
        statusCard.addView(status, marginTop(8));

        address = text("พร้อมใช้งาน", 16, true);
        address.setTextIsSelectable(true);
        statusCard.addView(address, marginTop(7));

        bridgeStatus = text("Bridge พร้อมใช้งาน", 13, false);
        bridgeStatus.setTextColor(theme.textSecondary);
        statusCard.addView(bridgeStatus, marginTop(5));

        homeSummary = text("", 12, false);
        homeSummary.setTextColor(theme.textSecondary);
        statusCard.addView(homeSummary, marginTop(10));
        root.addView(statusCard, marginTop(16));

        startStop = primaryButton("เปิดเซิร์ฟเวอร์");
        startStop.setOnClickListener(v -> toggleServer());
        root.addView(startStop, marginTop(14));

        Button quick = button("เริ่มแบบด่วน  //  QUICK START");
        quick.setOnClickListener(v -> {
            serverName.setText("Minecraft Voice");
            port.setText("64738");
            password.setText("");
            maxUsers.setText("20");
            voiceRange.setText("30");
            proximityEnabled.setChecked(false);
            saveSettings(false);
            if (!running) toggleServer();
        });
        root.addView(quick, marginTop(8));

        String nativeMode = NativeServer.hasMumbleCore()
                ? "MUMBLE CORE // พร้อมใช้งานด้วย stock Mumble/Mumla protocol"
                : "TRANSPORT TEST // build นี้ใช้สำหรับทดสอบ TCP/UDP เท่านั้น";
        TextView core = text(nativeMode, 11, false);
        core.setTextColor(theme.textSecondary);
        root.addView(core, marginTop(10));

        LinearLayout publicCard = card(false);
        TextView publicTitle = text("PUBLIC ACCESS // PORTWARP", 13, true);
        publicTitle.setTextColor(theme.accent);
        publicCard.addView(publicTitle);

        TextView publicBody = text(
                "ใช้คู่กับแอป PortWarp ทางการเพื่อเปิดเซิร์ฟเวอร์ออกอินเทอร์เน็ต "
                        + "สร้าง Tunnel แบบ TCP+UDP แล้วชี้มาที่ Local Host 127.0.0.1 "
                        + "และใช้พอร์ตเดียวกับ Mumble Server",
                12,
                false
        );
        publicBody.setTextColor(theme.textSecondary);
        publicCard.addView(publicBody, marginTop(7));

        portWarpTarget = text("เป้าหมาย PortWarp: 127.0.0.1:64738", 14, true);
        portWarpTarget.setTextColor(theme.accent);
        portWarpTarget.setTextIsSelectable(true);
        publicCard.addView(portWarpTarget, marginTop(10));

        Button portWarpPlayStoreButton = button("เปิด PORTWARP ใน GOOGLE PLAY");
        portWarpPlayStoreButton.setOnClickListener(v -> openPortWarpPlayStore());
        publicCard.addView(portWarpPlayStoreButton, marginTop(10));
        root.addView(publicCard, marginTop(18));

        TextView tip = text(
                "TIP // ตั้งค่าพอร์ตและ Proximity Voice ได้ที่เมนู “ตั้งค่า” ด้านล่าง",
                11,
                false
        );
        tip.setTextColor(theme.textSecondary);
        root.addView(tip, marginTop(12));

        return scroll(root);
    }

    private View buildLogPage() {
        LinearLayout root = pageContainer();

        TextView eyebrow = text("DIAGNOSTICS // LIVE", 11, true);
        eyebrow.setTextColor(theme.accent);
        eyebrow.setLetterSpacing(0.12f);
        root.addView(eyebrow);

        TextView title = text("LOG // TERMINAL", 25, true);
        theme.glowTitle(title);
        root.addView(title, marginTop(4));

        TextView note = text(
                "ข้อความภายใน Log จะคงเป็นภาษาอังกฤษเพื่อให้ง่ายต่อการตรวจสอบและส่ง debug",
                12,
                false
        );
        note.setTextColor(theme.textSecondary);
        root.addView(note, marginTop(5));

        logView = text("", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(theme.terminalText);
        logView.setTextIsSelectable(true);
        logView.setMinLines(20);
        logView.setPadding(dp(14), dp(14), dp(14), dp(14));
        logView.setBackground(theme.panel(dp(14), true));
        root.addView(logView, marginTop(14));

        Button copyLog = button("คัดลอก Log");
        copyLog.setOnClickListener(v -> copyLogToClipboard());
        root.addView(copyLog, marginTop(10));

        Button downloadLog = button("บันทึก log.txt");
        downloadLog.setOnClickListener(v -> exportLog());
        root.addView(downloadLog, marginTop(8));

        Button clearLog = button("ล้าง Log");
        clearLog.setTextColor(theme.danger);
        clearLog.setOnClickListener(v -> {
            ServerLog.clear(this);
            refreshLogView();
            Toast.makeText(this, "ล้าง Log แล้ว", Toast.LENGTH_SHORT).show();
        });
        root.addView(clearLog, marginTop(8));

        return scroll(root);
    }

    private View buildSettingsPage() {
        LinearLayout root = pageContainer();

        TextView eyebrow = text("CONFIG // SERVER", 11, true);
        eyebrow.setTextColor(theme.accent);
        eyebrow.setLetterSpacing(0.12f);
        root.addView(eyebrow);

        TextView title = text("ตั้งค่าเซิร์ฟเวอร์", 25, true);
        root.addView(title, marginTop(4));

        TextView desc = text(
                "การตั้งค่าจะถูกบันทึกในเครื่องและใช้เมื่อเปิดเซิร์ฟเวอร์ครั้งถัดไป",
                12,
                false
        );
        desc.setTextColor(theme.textSecondary);
        root.addView(desc, marginTop(5));

        LinearLayout serverCard = card(false);
        TextView serverSection = text("MUMBLE SERVER", 13, true);
        serverSection.setTextColor(theme.accent);
        serverCard.addView(serverSection);

        serverName = field("Minecraft Voice", InputType.TYPE_CLASS_TEXT);
        addLabeledField(serverCard, "ชื่อเซิร์ฟเวอร์", serverName);

        port = field("64738", InputType.TYPE_CLASS_NUMBER);
        addLabeledField(serverCard, "พอร์ต Mumble", port);

        password = field(
                "ไม่บังคับ",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        addLabeledField(serverCard, "รหัสผ่านเซิร์ฟเวอร์", password);

        maxUsers = field("20", InputType.TYPE_CLASS_NUMBER);
        addLabeledField(serverCard, "จำนวนผู้เล่นสูงสุด", maxUsers);
        root.addView(serverCard, marginTop(14));

        LinearLayout proximityCard = card(false);
        TextView proximityTitle = text("MINECRAFT // PROXIMITY VOICE", 13, true);
        proximityTitle.setTextColor(theme.accent);
        proximityCard.addView(proximityTitle);

        voiceRange = field("30", InputType.TYPE_CLASS_NUMBER);
        addLabeledField(proximityCard, "ระยะเสียง (บล็อก)", voiceRange);

        proximityEnabled = new CheckBox(this);
        proximityEnabled.setText("เปิดใช้งาน Proximity Voice");
        proximityEnabled.setTextSize(15);
        proximityEnabled.setTextColor(theme.textPrimary);
        proximityEnabled.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));
        proximityEnabled.setOnCheckedChangeListener((buttonView, checked) -> updateBridgeFieldVisibility());
        proximityCard.addView(proximityEnabled, marginTop(10));

        TextView proximityNote = text(
                "VC Mumble Endstone จะส่งชื่อผู้ใช้ Mumble, dimension และระยะเสียง "
                        + "หากชื่อใน Mumble ไม่ตรงกับ Minecraft ให้ใช้คำสั่ง /vcmumble pair <name>",
                11,
                false
        );
        proximityNote.setTextColor(theme.textSecondary);
        proximityCard.addView(proximityNote, marginTop(4));

        bridgeHost = field(
                "เช่น sv5.mcsv.me",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
        );
        addLabeledField(proximityCard, "โฮสต์ Minecraft Server", bridgeHost);

        bridgePort = field("พอร์ต Bridge", InputType.TYPE_CLASS_NUMBER);
        addLabeledField(proximityCard, "พอร์ต VC Mumble Bridge", bridgePort);

        bridgeSecret = field(
                "Bridge Secret",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        addLabeledField(proximityCard, "Bridge Secret", bridgeSecret);

        TextView secretNote = text(
                "Bridge Secret จะถูกเข้ารหัสด้วย Android Keystore ก่อนบันทึกลงเครื่อง",
                11,
                false
        );
        secretNote.setTextColor(theme.textSecondary);
        proximityCard.addView(secretNote, marginTop(5));
        root.addView(proximityCard, marginTop(12));

        Button save = primaryButton("บันทึกการตั้งค่า");
        save.setOnClickListener(v -> saveSettings(true));
        root.addView(save, marginTop(14));

        TextView systemTheme = text(
                theme.dark
                        ? "THEME // ระบบกำลังใช้โหมดมืด • Cyber Navy + Neon Cyan"
                        : "THEME // ระบบกำลังใช้โหมดสว่าง • Ice Blue + Neon Cyan",
                11,
                false
        );
        systemTheme.setTextColor(theme.textSecondary);
        root.addView(systemTheme, marginTop(10));

        return scroll(root);
    }

    private View buildBottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(10), dp(8), dp(10), dp(10));
        nav.setBackgroundColor(theme.surface);
        nav.setGravity(Gravity.CENTER);

        navHome = navButton("⌂  หน้าหลัก");
        navLog = navButton("▤  Log");
        navSettings = navButton("⚙  ตั้งค่า");

        navHome.setOnClickListener(v -> showPage(PAGE_HOME));
        navLog.setOnClickListener(v -> {
            refreshLogView();
            showPage(PAGE_LOG);
        });
        navSettings.setOnClickListener(v -> showPage(PAGE_SETTINGS));

        LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
        );
        item.setMargins(dp(3), 0, dp(3), 0);
        nav.addView(navHome, item);
        nav.addView(navLog, item);
        nav.addView(navSettings, item);
        return nav;
    }

    private void showPage(int page) {
        homePage.setVisibility(page == PAGE_HOME ? View.VISIBLE : View.GONE);
        logPage.setVisibility(page == PAGE_LOG ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(page == PAGE_SETTINGS ? View.VISIBLE : View.GONE);

        styleNav(navHome, page == PAGE_HOME);
        styleNav(navLog, page == PAGE_LOG);
        styleNav(navSettings, page == PAGE_SETTINGS);

        if (headerPageName != null) {
            headerPageName.setText(
                    page == PAGE_HOME ? "หน้าหลัก"
                            : page == PAGE_LOG ? "Log"
                            : "ตั้งค่า"
            );
        }
    }

    private void toggleServer() {
        if (running) {
            ServerLog.append(this, "UI", "Stop server button pressed");
            startService(new Intent(this, MumbleServerService.class)
                    .setAction(MumbleServerService.ACTION_STOP));
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
            refreshHomeSummary(cfg);
            refreshLogView();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            ServerLog.append(
                    this,
                    "ERROR",
                    "Unable to start: " + e.getClass().getSimpleName()
                            + ": " + String.valueOf(e.getMessage())
            );
            Toast.makeText(
                    this,
                    "เริ่มเซิร์ฟเวอร์ไม่ได้: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        Intent intent = new Intent(this, MumbleServerService.class)
                .setAction(MumbleServerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void saveSettings(boolean showToast) {
        try {
            ServerConfig cfg = readConfig();
            cfg.save(this);
            refreshHomeSummary(cfg);
            ServerLog.append(
                    this,
                    "UI",
                    "Server settings saved; port=" + cfg.port
                            + ", maxUsers=" + cfg.maxUsers
                            + ", voiceRange=" + cfg.voiceRange
                            + ", proximity=" + cfg.proximityEnabled
            );
            if (showToast) {
                Toast.makeText(this, "บันทึกการตั้งค่าแล้ว", Toast.LENGTH_SHORT).show();
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            if (showToast) {
                Toast.makeText(
                        this,
                        "ตรวจสอบการตั้งค่า: " + error.getMessage(),
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private ServerConfig readConfig() {
        String name = serverName.getText().toString().trim();
        if (name.isEmpty()) name = "Minecraft Voice";

        int serverPort = parseInt(port, "Port", 1, 65535);
        int users = parseInt(maxUsers, "Max players", 1, 1000);
        int range = parseInt(voiceRange, "Voice range", 1, 1000);
        String bridgeHostValue = bridgeHost.getText().toString().trim();
        int bridgePortValue = parseInt(bridgePort, "Bridge port", 1, 65535);
        String secret = bridgeSecret.getText().toString();
        boolean proximity = proximityEnabled.isChecked();

        if (proximity) {
            if (bridgeHostValue.isEmpty()) {
                throw new IllegalArgumentException("Minecraft bridge host is required");
            }
            if (secret.isEmpty()) {
                throw new IllegalArgumentException("Bridge secret is required for proximity mode");
            }
        }

        return new ServerConfig(
                name,
                serverPort,
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
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " must be " + min + "–" + max);
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
        refreshHomeSummary(config);
    }

    private void refreshHomeSummary(ServerConfig config) {
        if (homeSummary != null) {
            homeSummary.setText(
                    "PORT " + config.port
                            + "   •   MAX " + config.maxUsers
                            + "   •   RANGE " + config.voiceRange + " BLOCKS"
            );
        }
        if (portWarpTarget != null) {
            portWarpTarget.setText("เป้าหมาย PortWarp: 127.0.0.1:" + config.port);
        }
    }

    private void updateState(
            boolean isRunning,
            String message,
            String bridge,
            int tracked
    ) {
        running = isRunning;
        boolean starting = isRunning && message != null && message.startsWith("Starting");

        if (starting) {
            status.setText("● กำลังเริ่มระบบ");
            status.setTextColor(theme.warning);
        } else {
            status.setText(isRunning ? "● ออนไลน์" : "● ออฟไลน์");
            status.setTextColor(isRunning ? theme.success : theme.danger);
        }

        address.setText(localizeStatusMessage(message));
        String bridgeText = localizeBridgeMessage(bridge);
        if (tracked > 0) {
            bridgeText += " • " + tracked + " ผู้เล่น Minecraft";
        }
        bridgeStatus.setText(bridgeText);

        startStop.setText(isRunning ? "หยุดเซิร์ฟเวอร์" : "เปิดเซิร์ฟเวอร์");
        startStop.setBackground(
                isRunning
                        ? theme.button(dp(14), false)
                        : theme.button(dp(14), true)
        );
        startStop.setTextColor(isRunning ? theme.danger : Color.rgb(0, 28, 38));

        setFieldsEnabled(!isRunning);
    }

    private String localizeStatusMessage(String message) {
        if (message == null || message.isEmpty() || "Ready".equals(message)) {
            return "พร้อมใช้งาน";
        }
        if (message.startsWith("Starting")) {
            return "กำลังเริ่ม Mumble Server…";
        }
        return message;
    }

    private String localizeBridgeMessage(String message) {
        if (message == null || message.isEmpty() || "Bridge idle".equals(message)) {
            return "Bridge พร้อมใช้งาน";
        }
        return message;
    }

    private void setFieldsEnabled(boolean enabled) {
        serverName.setEnabled(enabled);
        port.setEnabled(enabled);
        password.setEnabled(enabled);
        maxUsers.setEnabled(enabled);
        voiceRange.setEnabled(enabled);
        proximityEnabled.setEnabled(enabled && NativeServer.hasMumbleCore());

        boolean bridgeEnabled = enabled
                && proximityEnabled.isChecked()
                && NativeServer.hasMumbleCore();
        bridgeHost.setEnabled(bridgeEnabled);
        bridgePort.setEnabled(bridgeEnabled);
        bridgeSecret.setEnabled(bridgeEnabled);
    }

    private void updateBridgeFieldVisibility() {
        if (bridgeHost == null || bridgePort == null || bridgeSecret == null) return;
        boolean enabled = !running
                && proximityEnabled.isChecked()
                && NativeServer.hasMumbleCore();
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
                            "Mumble process detected",
                            0
                    );
                } else if (!running) {
                    updateState(false, "Ready", "Bridge idle", 0);
                }
            });
        }, "VCMumble-UI-State-Probe").start();
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
            Toast.makeText(
                    this,
                    "ไม่พบ Browser จึงคัดลอกลิงก์ไว้ใน Clipboard แล้ว",
                    Toast.LENGTH_LONG
            ).show();
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
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
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
        if (requestCode != REQUEST_EXPORT_LOG
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        try (OutputStream output =
                     getContentResolver().openOutputStream(data.getData(), "w")) {
            if (output == null) {
                throw new IOException("Unable to open selected destination");
            }
            output.write(ServerLog.readAllBytes(this));
            output.flush();
            Toast.makeText(this, "บันทึก log.txt แล้ว", Toast.LENGTH_SHORT).show();
        } catch (IOException error) {
            ServerLog.append(
                    this,
                    "ERROR",
                    "Log export failed: " + error.getClass().getSimpleName()
                            + ": " + String.valueOf(error.getMessage())
            );
            refreshLogView();
            Toast.makeText(
                    this,
                    "บันทึก log.txt ไม่สำเร็จ",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    100
            );
        }
    }

    private LinearLayout pageContainer() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));
        root.setBackgroundColor(theme.background);
        return root;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(theme.background);
        scroll.addView(child);
        return scroll;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(theme.textPrimary);
        view.setFontFeatureSettings("kern");
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        } else {
            view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        return view;
    }

    private EditText field(String hint, int type) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(type);
        input.setTextSize(15);
        input.setTextColor(theme.textPrimary);
        input.setHintTextColor(theme.textSecondary);
        input.setPadding(dp(13), dp(11), dp(13), dp(11));
        input.setMinHeight(dp(48));
        input.setBackground(theme.field(dp(12)));
        return input;
    }

    private void addLabeledField(LinearLayout parent, String label, EditText input) {
        TextView labelView = text(label, 12, true);
        labelView.setTextColor(theme.textSecondary);
        parent.addView(labelView, marginTop(12));
        parent.addView(input, marginTop(5));
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(theme.accent);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        button.setMinHeight(dp(50));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(theme.button(dp(14), false));
        button.setElevation(dp(2));
        return button;
    }

    private Button primaryButton(String label) {
        Button button = button(label);
        button.setTextColor(Color.rgb(0, 28, 38));
        button.setBackground(theme.button(dp(14), true));
        button.setElevation(dp(4));
        return button;
    }

    private Button navButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        button.setPadding(dp(5), 0, dp(5), 0);
        return button;
    }

    private void styleNav(Button button, boolean selected) {
        button.setTextColor(selected ? theme.accent : theme.textSecondary);
        button.setBackground(theme.navButton(dp(13), selected));
        button.setElevation(selected ? dp(3) : 0);
    }

    private LinearLayout card(boolean strong) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(theme.panel(dp(16), strong));
        card.setElevation(dp(2));
        return card;
    }

    private LinearLayout.LayoutParams marginTop(int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(marginDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
