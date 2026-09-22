package com.voicecraft.vcmumbleserver;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
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
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Base64;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public final class MainActivity extends Activity {
    private static final int REQUEST_EXPORT_LOG = 4201;
    private static final int REQUEST_EXPORT_ENDSTONE_PLUGIN = 4202;
    private static final int REQUEST_EXPORT_ENDSTONE_CONFIG = 4203;
    private static final int PAGE_HOME = 0;
    private static final int PAGE_LOG = 1;
    private static final int PAGE_SETTINGS = 2;

    private TextView status;
    private TextView address;
    private TextView bridgeStatus;
    private LinearLayout statusCard;
    private TextView portWarpTarget;
    private TextView endstonePluginStatus;

    private EditText serverName;
    private EditText port;
    private EditText password;
    private EditText maxUsers;
    private EditText voiceRange;
    private EditText bridgeHost;
    private EditText bridgePort;
    private EditText bridgeSecret;

    private Button startStop;
    private TextView logView;
    private ScrollView logScroll;

    private View homePage;
    private View logPage;
    private View settingsPage;
    private Button navHome;
    private Button navLog;
    private Button navSettings;

    private boolean running;
    private int currentPage = -1;
    private ObjectAnimator statusPulse;
    private EndstoneReleaseResolver.ReleaseInfo pendingEndstoneRelease;
    private String pendingEndstoneBridgeSecret = "";
    private String pendingEndstoneConfig = "";

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
        updateState(false, "พร้อมเริ่มเซิร์ฟเวอร์", "Minecraft: ยังไม่ได้เชื่อมต่อ", 0);
        showPage(PAGE_HOME);
        refreshEndstoneReleaseStatus();
        if (savedInstanceState == null) {
            showBatteryAccessPromptIfNeeded();
        }
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
        stopStatusPulse();
        super.onStop();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(c(R.color.cyber_bg));

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
                "",
                "เซิร์ฟเวอร์",
                "เปิดและดูสถานะ Mumble Server"
        );

        statusCard = card();
        TextView statusLabel = eyebrow("สถานะ");
        statusCard.addView(statusLabel);

        status = text("ออฟไลน์", 24, true);
        statusCard.addView(status, marginTop(8));

        address = text("พร้อมเริ่มเซิร์ฟเวอร์", 15, false);
        address.setTextIsSelectable(true);
        statusCard.addView(address, marginTop(8));

        bridgeStatus = text("Minecraft: ยังไม่ได้เชื่อมต่อ", 12, false);
        bridgeStatus.setTextColor(c(R.color.cyber_text_secondary));
        statusCard.addView(bridgeStatus, marginTop(8));

        root.addView(statusCard, marginTop(18));

        startStop = primaryButton("เปิดเซิร์ฟเวอร์");
        startStop.setOnClickListener(v -> toggleServer());
        root.addView(startStop, marginTop(14));

        Button quick = secondaryButton("ใช้ค่าเริ่มต้นและเปิด");
        quick.setOnClickListener(v -> {
            serverName.setText("Minecraft Voice");
            port.setText("64738");
            password.setText("");
            maxUsers.setText("20");
            voiceRange.setText("30");
            if (!running) toggleServer();
        });
        root.addView(quick, marginTop(8));

        root.addView(buildProximityCard(), marginTop(14));
        root.addView(buildEndstonePluginCard(), marginTop(14));

        LinearLayout publicCard = card();
        publicCard.addView(eyebrow("ใช้งานผ่านอินเทอร์เน็ต"));
        TextView publicNote = text(
                "ใช้ PortWarp เพื่อให้คนนอกเครือข่ายเดียวกันเชื่อมต่อเซิร์ฟเวอร์ได้",
                13,
                false
        );
        publicNote.setTextColor(c(R.color.cyber_text_secondary));
        publicCard.addView(publicNote, marginTop(8));

        portWarpTarget = text(
                "ปลายทาง: 127.0.0.1:" + ServerConfig.load(this).port,
                14,
                true
        );
        portWarpTarget.setTextColor(c(R.color.cyber_neon));
        portWarpTarget.setTextIsSelectable(true);
        publicCard.addView(portWarpTarget, marginTop(10));

        Button playStore = secondaryButton("เปิด PortWarp");
        playStore.setOnClickListener(v -> openPortWarpPlayStore());
        publicCard.addView(playStore, marginTop(10));
        root.addView(publicCard, marginTop(14));

        return wrapScroll(root);
    }

    private View buildProximityCard() {
        LinearLayout proximityCard = card();
        proximityCard.addView(eyebrow("Minecraft Proximity"));

        TextView alwaysOn = text("เปิดตลอด", 13, true);
        alwaysOn.setTextColor(c(R.color.cyber_success));
        proximityCard.addView(alwaysOn, marginTop(8));

        TextView proximityNote = text(
                "กำหนดระยะที่ผู้เล่นจะได้ยินกันใน Minecraft",
                12,
                false
        );
        proximityNote.setTextColor(c(R.color.cyber_text_secondary));
        proximityCard.addView(proximityNote, marginTop(6));

        voiceRange = field("ระยะเสียง (บล็อก)", InputType.TYPE_CLASS_NUMBER);
        addLabeledField(proximityCard, "ระยะเสียง", voiceRange);

        bridgeHost = field(
                "เช่น sv5.mcsv.me หรือ IP ของเซิร์ฟเวอร์",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
        );
        bridgePort = field("VC Mumble Bridge Port", InputType.TYPE_CLASS_NUMBER);
        bridgeSecret = field(
                "Bridge Secret",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        addLabeledField(proximityCard, "Minecraft Server", bridgeHost);
        addLabeledField(proximityCard, "Bridge Port", bridgePort);
        addLabeledField(proximityCard, "Bridge Secret", bridgeSecret);

        LinearLayout secretActions = new LinearLayout(this);
        secretActions.setOrientation(LinearLayout.HORIZONTAL);

        Button generateSecret = secondaryButton("สุ่มใหม่");
        generateSecret.setOnClickListener(v -> generateBridgeSecret());
        Button copySecret = secondaryButton("คัดลอก");
        copySecret.setOnClickListener(v -> copyBridgeSecret());

        LinearLayout.LayoutParams secretButton = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        secretButton.setMarginEnd(dp(4));
        secretActions.addView(generateSecret, secretButton);

        LinearLayout.LayoutParams copyButton = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        copyButton.setMarginStart(dp(4));
        secretActions.addView(copySecret, copyButton);
        proximityCard.addView(secretActions, marginTop(8));

        TextView secretNote = text(
                "Secret จะถูกเก็บแบบเข้ารหัสในเครื่อง",
                11,
                false
        );
        secretNote.setTextColor(c(R.color.cyber_text_secondary));
        proximityCard.addView(secretNote, marginTop(6));

        Button saveProximity = primaryButton("บันทึก");
        saveProximity.setOnClickListener(v -> {
            try {
                saveSettings(true);
            } catch (Exception error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        proximityCard.addView(saveProximity, marginTop(10));

        return proximityCard;
    }

    private View buildEndstonePluginCard() {
        LinearLayout pluginCard = card();
        pluginCard.addView(eyebrow("Endstone Plugin"));

        TextView note = text(
                "ดาวน์โหลดปลั๊กอินจาก GitHub Release ล่าสุด และใส่ Bridge Secret ให้อัตโนมัติ",
                12,
                false
        );
        note.setTextColor(c(R.color.cyber_text_secondary));
        pluginCard.addView(note, marginTop(7));

        endstonePluginStatus = text("เวอร์ชันล่าสุด: กำลังตรวจสอบ...", 12, true);
        endstonePluginStatus.setTextColor(c(R.color.cyber_neon));
        endstonePluginStatus.setTextIsSelectable(true);
        pluginCard.addView(endstonePluginStatus, marginTop(9));

        Button downloadPlugin = primaryButton("ดาวน์โหลด Plugin (.whl)");
        downloadPlugin.setOnClickListener(v -> prepareEndstonePluginDownload());
        pluginCard.addView(downloadPlugin, marginTop(10));

        Button downloadConfig = secondaryButton("ดาวน์โหลด config.toml");
        downloadConfig.setOnClickListener(v -> prepareEndstoneConfigExport());
        pluginCard.addView(downloadConfig, marginTop(8));

        TextView securityNote = text(
                "ไฟล์ที่ดาวน์โหลดจะมี Bridge Secret ของเครื่องนี้อยู่ภายใน กรุณาเก็บไฟล์เป็นส่วนตัว",
                11,
                false
        );
        securityNote.setTextColor(c(R.color.cyber_text_secondary));
        pluginCard.addView(securityNote, marginTop(7));
        return pluginCard;
    }

    private View buildLogPage() {
        LinearLayout root = pageRoot();
        addScreenHeader(
                root,
                "",
                "Log",
                "บันทึกการทำงานของเซิร์ฟเวอร์"
        );

        LinearLayout terminal = card();
        terminal.setBackground(rounded(
                c(R.color.cyber_log_bg),
                c(R.color.cyber_neon_dim),
                14,
                1
        ));

        TextView terminalTitle = text("Server Log", 12, true);
        terminalTitle.setTextColor(c(R.color.cyber_neon));
        terminal.addView(terminalTitle);

        logView = text("", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(c(R.color.cyber_log_text));
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(2), dp(10), dp(8), dp(12));

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setScrollbarFadingEnabled(false);
        logScroll.setNestedScrollingEnabled(true);
        logScroll.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams logViewport = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360)
        );
        logViewport.topMargin = dp(8);
        terminal.addView(logScroll, logViewport);
        root.addView(terminal, marginTop(18));

        Button copyLog = secondaryButton("คัดลอก");
        copyLog.setOnClickListener(v -> copyLogToClipboard());
        root.addView(copyLog, marginTop(10));

        Button downloadLog = secondaryButton("บันทึกไฟล์");
        downloadLog.setOnClickListener(v -> exportLog());
        root.addView(downloadLog, marginTop(8));

        Button clearLog = dangerButton("ล้าง");
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
                "",
                "ตั้งค่า",
                "ตั้งค่า Mumble Server"
        );

        LinearLayout serverCard = card();
        serverCard.addView(eyebrow("Mumble Server"));

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

        Button save = primaryButton("บันทึก");
        save.setOnClickListener(v -> {
            try {
                saveSettings(true);
            } catch (Exception error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(save, marginTop(14));

        TextView stopHint = text(
                "ปิดเซิร์ฟเวอร์ก่อนแก้ไขค่าเหล่านี้",
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
        navLog = navButton("Log");
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
        View next = pageView(page);
        if (next == null) return;

        int previousPage = currentPage;
        View previous = pageView(previousPage);

        styleNavButton(navHome, page == PAGE_HOME);
        styleNavButton(navLog, page == PAGE_LOG);
        styleNavButton(navSettings, page == PAGE_SETTINGS);

        if (previousPage == page && next.getVisibility() == View.VISIBLE) {
            if (page == PAGE_LOG) refreshLogView();
            if (page == PAGE_HOME) {
                refreshServerStateFromTcp();
                updatePortWarpTarget();
            }
            return;
        }

        currentPage = page;
        int direction = previousPage < 0 || page > previousPage ? 1 : -1;

        hideUnusedPage(homePage, previous, next);
        hideUnusedPage(logPage, previous, next);
        hideUnusedPage(settingsPage, previous, next);

        if (previous != null && previous != next && previous.getVisibility() == View.VISIBLE) {
            previous.animate().cancel();
            previous.animate()
                    .alpha(0f)
                    .translationX(-direction * dp(18))
                    .setDuration(130)
                    .withEndAction(() -> {
                        previous.setVisibility(View.GONE);
                        previous.setAlpha(1f);
                        previous.setTranslationX(0f);
                    })
                    .start();
        }

        next.animate().cancel();
        next.setVisibility(View.VISIBLE);
        next.setAlpha(0f);
        next.setTranslationX(direction * dp(18));
        next.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(190)
                .start();

        if (page == PAGE_LOG) refreshLogView();
        if (page == PAGE_HOME) {
            refreshServerStateFromTcp();
            updatePortWarpTarget();
        }
    }

    private View pageView(int page) {
        if (page == PAGE_HOME) return homePage;
        if (page == PAGE_LOG) return logPage;
        if (page == PAGE_SETTINGS) return settingsPage;
        return null;
    }

    private void hideUnusedPage(View candidate, View previous, View next) {
        if (candidate != previous && candidate != next) {
            candidate.animate().cancel();
            candidate.setVisibility(View.GONE);
            candidate.setAlpha(1f);
            candidate.setTranslationX(0f);
        }
    }

    private void toggleServer() {
        if (running) {
            ServerRuntimeState.setShouldRun(this, false);
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

        String targetAddress = NetworkUtil.bestLanIpv4() + ":" + cfg.port;
        ServerRuntimeState.setShouldRun(this, true);
        updateState(true, "Starting • " + targetAddress, "Minecraft: กำลังเชื่อมต่อ", 0);

        Intent i = new Intent(this, MumbleServerService.class).setAction(MumbleServerService.ACTION_START);
        try {
            ServerLog.append(this, "UI", "Dispatching Mumble service start; target=" + targetAddress);
            ComponentName component = Build.VERSION.SDK_INT >= 26
                    ? startForegroundService(i)
                    : startService(i);
            if (component == null) {
                throw new IllegalStateException("Android did not accept the Mumble service start request");
            }
            ServerLog.append(this, "UI", "Mumble service start dispatched; component="
                    + component.flattenToShortString());
            refreshLogView();
        } catch (RuntimeException error) {
            ServerRuntimeState.setShouldRun(this, false);
            String message = "เปิด Mumble Service ไม่สำเร็จ: "
                    + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
            ServerLog.append(this, "ERROR", message);
            updateState(false, message, "Minecraft: ยังไม่ได้เชื่อมต่อ", 0);
            refreshLogView();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
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
        if (showToast) Toast.makeText(this, "บันทึกแล้ว", Toast.LENGTH_SHORT).show();
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
        boolean proximity = true;

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
        bridgeHost.setText(config.bridgeHost);
        bridgePort.setText(String.valueOf(config.bridgePort));
        bridgeSecret.setText(config.bridgeSecret);
        updatePortWarpTarget();
    }

    private void updateState(boolean isRunning, String message, String bridge, int tracked) {
        running = isRunning;
        boolean starting = isRunning && message != null && message.startsWith("Starting");

        if (starting) {
            status.setText("กำลังเริ่ม");
            status.setTextColor(c(R.color.cyber_warning));
            statusCard.setBackground(rounded(
                    c(R.color.cyber_surface),
                    c(R.color.cyber_warning),
                    16,
                    1
            ));
        } else if (isRunning) {
            status.setText("ออนไลน์");
            status.setTextColor(c(R.color.cyber_success));
            statusCard.setBackground(rounded(
                    c(R.color.cyber_surface),
                    c(R.color.cyber_success),
                    16,
                    1
            ));
        } else {
            status.setText("ออฟไลน์");
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
        if (bridgeText.startsWith("Bridge config missing")) {
            bridgeText = "Minecraft: ยังไม่ได้ตั้งค่า";
        } else if (bridgeText.startsWith("Minecraft bridge connected")) {
            bridgeText = "Minecraft: เชื่อมต่อแล้ว";
        } else if (bridgeText.startsWith("Minecraft bridge error")) {
            bridgeText = "Minecraft: เชื่อมต่อไม่สำเร็จ";
        } else if ("Bridge idle".equals(bridgeText) || bridgeText.isEmpty()) {
            bridgeText = "Minecraft: ยังไม่ได้เชื่อมต่อ";
        }
        if (tracked > 0) {
            bridgeText += " • " + tracked + " คน";
        }
        bridgeStatus.setText(bridgeText);

        startStop.setText(isRunning ? "ปิดเซิร์ฟเวอร์" : "เปิดเซิร์ฟเวอร์");
        setFieldsEnabled(!isRunning);
        updateStatusPulse(starting, isRunning);
    }

    private void updateStatusPulse(boolean starting, boolean isRunning) {
        stopStatusPulse();
        if (!starting && !isRunning) return;

        statusPulse = ObjectAnimator.ofFloat(status, View.ALPHA, 1f, 0.58f, 1f);
        statusPulse.setDuration(starting ? 900 : 1500);
        statusPulse.setRepeatCount(ValueAnimator.INFINITE);
        statusPulse.setRepeatMode(ValueAnimator.RESTART);
        statusPulse.start();
    }

    private void stopStatusPulse() {
        if (statusPulse != null) {
            statusPulse.cancel();
            statusPulse = null;
        }
        if (status != null) status.setAlpha(1f);
    }

    private void setFieldsEnabled(boolean enabled) {
        serverName.setEnabled(enabled);
        port.setEnabled(enabled);
        password.setEnabled(enabled);
        maxUsers.setEnabled(enabled);
        voiceRange.setEnabled(enabled);
        bridgeHost.setEnabled(enabled && NativeServer.hasMumbleCore());
        bridgePort.setEnabled(enabled && NativeServer.hasMumbleCore());
        bridgeSecret.setEnabled(enabled && NativeServer.hasMumbleCore());
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
                            "เซิร์ฟเวอร์กำลังทำงาน",
                            0
                    );
                } else if (!running) {
                    updateState(false, "พร้อมเริ่มเซิร์ฟเวอร์", "Minecraft: ยังไม่ได้เชื่อมต่อ", 0);
                }
            });
        }, "VCMumble-UI-State-Probe").start();
    }

    private void updatePortWarpTarget() {
        if (portWarpTarget != null) {
            portWarpTarget.setText(
                    "ปลายทาง: 127.0.0.1:" + ServerConfig.load(this).port
            );
        }
    }

    private void generateBridgeSecret() {
        String secret = newBridgeSecret();
        bridgeSecret.setText(secret);
        bridgeSecret.setSelection(secret.length());
        Toast.makeText(this, "สุ่ม Secret แล้ว", Toast.LENGTH_SHORT).show();
    }

    private void copyBridgeSecret() {
        String secret = bridgeSecret.getText().toString();
        if (secret.isEmpty()) {
            generateBridgeSecret();
            secret = bridgeSecret.getText().toString();
        }
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("VC Mumble Bridge Secret", secret));
        Toast.makeText(this, "คัดลอกแล้ว", Toast.LENGTH_SHORT).show();
    }

    private void refreshEndstoneReleaseStatus() {
        if (endstonePluginStatus == null) return;
        endstonePluginStatus.setText("เวอร์ชันล่าสุด: กำลังตรวจสอบ...");
        new Thread(() -> {
            try {
                EndstoneReleaseResolver.ReleaseInfo release = EndstoneReleaseResolver.resolveLatest();
                runOnUiThread(() -> endstonePluginStatus.setText(
                        "เวอร์ชันล่าสุด: " + release.tagName + " • " + release.wheelName
                ));
            } catch (Exception error) {
                runOnUiThread(() -> endstonePluginStatus.setText(
                        "เวอร์ชันล่าสุด: ตรวจสอบไม่สำเร็จ • กดดาวน์โหลดเพื่อลองใหม่"
                ));
            }
        }, "VCMumble-Endstone-Version").start();
    }

    private void prepareEndstonePluginDownload() {
        final String secret;
        try {
            secret = ensureBridgeSecretForExport();
        } catch (Exception error) {
            Toast.makeText(this, "เตรียม Bridge Secret ไม่สำเร็จ: " + error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        endstonePluginStatus.setText("กำลังตรวจสอบ GitHub Release ล่าสุด...");
        new Thread(() -> {
            try {
                EndstoneReleaseResolver.ReleaseInfo release = EndstoneReleaseResolver.resolveLatest();
                if (!release.hasChecksumAsset()) {
                    throw new IOException("Release " + release.tagName + " ไม่มี SHA256SUMS.txt");
                }
                runOnUiThread(() -> {
                    pendingEndstoneRelease = release;
                    pendingEndstoneBridgeSecret = secret;
                    endstonePluginStatus.setText(
                            "เลือกแล้ว: " + release.tagName + " • " + release.wheelName
                    );

                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    intent.putExtra(Intent.EXTRA_TITLE, release.wheelName);
                    startActivityForResult(intent, REQUEST_EXPORT_ENDSTONE_PLUGIN);
                });
            } catch (Exception error) {
                ServerLog.append(
                        this,
                        "ENDSTONE",
                        "Release lookup failed: " + error.getClass().getSimpleName()
                                + ": " + String.valueOf(error.getMessage())
                );
                runOnUiThread(() -> {
                    endstonePluginStatus.setText("ตรวจสอบ Release ไม่สำเร็จ");
                    refreshLogView();
                    Toast.makeText(
                            this,
                            "หา Endstone Plugin ล่าสุดไม่สำเร็จ: " + error.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        }, "VCMumble-Endstone-Release").start();
    }

    private String ensureBridgeSecretForExport() {
        String secret = bridgeSecret.getText().toString().trim();
        if (secret.isEmpty()) {
            secret = newBridgeSecret();
            bridgeSecret.setText(secret);
            bridgeSecret.setSelection(secret.length());
        }
        SecretStore.save(this, secret);
        ServerLog.append(this, "ENDSTONE", "Bridge Secret prepared for configured plugin export");
        return secret;
    }

    private String newBridgeSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );
    }

    private void downloadConfiguredEndstonePlugin(Uri destination) {
        final EndstoneReleaseResolver.ReleaseInfo release = pendingEndstoneRelease;
        final String secret = pendingEndstoneBridgeSecret;
        pendingEndstoneRelease = null;
        pendingEndstoneBridgeSecret = "";

        if (release == null || secret.isEmpty()) {
            Toast.makeText(this, "ข้อมูลการดาวน์โหลดหมดอายุ กรุณากดดาวน์โหลดใหม่", Toast.LENGTH_LONG).show();
            return;
        }

        endstonePluginStatus.setText("กำลังดาวน์โหลดและตรวจ SHA-256...");
        new Thread(() -> {
            try {
                EndstonePluginPackage.ConfiguredPlugin plugin =
                        EndstonePluginPackage.downloadAndConfigure(release, secret);

                try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new IOException("Unable to open selected destination");
                    output.write(plugin.bytes);
                    output.flush();
                }

                ServerLog.append(
                        this,
                        "ENDSTONE",
                        "Configured plugin saved; release=" + release.tagName
                                + ", asset=" + release.wheelName
                                + ", sourceSha256=" + plugin.sourceSha256
                                + ", configuredSha256=" + plugin.configuredSha256
                );
                runOnUiThread(() -> {
                    endstonePluginStatus.setText(
                            "พร้อมใช้: " + release.tagName + " • SHA-256 ผ่าน"
                    );
                    refreshLogView();
                    Toast.makeText(this, "ดาวน์โหลด Endstone Plugin พร้อม Bridge Secret แล้ว", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                ServerLog.append(
                        this,
                        "ENDSTONE",
                        "Plugin download failed: " + error.getClass().getSimpleName()
                                + ": " + String.valueOf(error.getMessage())
                );
                runOnUiThread(() -> {
                    endstonePluginStatus.setText("ดาวน์โหลด Plugin ไม่สำเร็จ");
                    refreshLogView();
                    Toast.makeText(
                            this,
                            "ดาวน์โหลด Endstone Plugin ไม่สำเร็จ: " + error.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        }, "VCMumble-Endstone-Download").start();
    }

    private void prepareEndstoneConfigExport() {
        try {
            String secret = ensureBridgeSecretForExport();
            int bridgePortValue = parseInt(bridgePort, "Bridge Port", 1, 65535);
            int rangeValue = parseInt(voiceRange, "ระยะเสียง", 1, 1000);
            pendingEndstoneConfig = EndstonePluginPackage.buildConfigToml(
                    secret,
                    bridgePortValue,
                    rangeValue
            );

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "config.toml");
            startActivityForResult(intent, REQUEST_EXPORT_ENDSTONE_CONFIG);
        } catch (Exception error) {
            Toast.makeText(this, "เตรียม config.toml ไม่สำเร็จ: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void writeEndstoneConfig(Uri destination) {
        String config = pendingEndstoneConfig;
        pendingEndstoneConfig = "";
        if (config.isEmpty()) {
            Toast.makeText(this, "ข้อมูล config หมดอายุ กรุณากดดาวน์โหลดใหม่", Toast.LENGTH_LONG).show();
            return;
        }

        try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException("Unable to open selected destination");
            output.write(config.getBytes(StandardCharsets.UTF_8));
            output.flush();
            ServerLog.append(this, "ENDSTONE", "Configured Endstone config.toml saved");
            refreshLogView();
            Toast.makeText(this, "บันทึก config.toml แล้ว", Toast.LENGTH_SHORT).show();
        } catch (IOException error) {
            ServerLog.append(
                    this,
                    "ENDSTONE",
                    "Config export failed: " + error.getClass().getSimpleName()
                            + ": " + String.valueOf(error.getMessage())
            );
            refreshLogView();
            Toast.makeText(this, "บันทึก config.toml ไม่สำเร็จ: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showBatteryAccessPromptIfNeeded() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null
                || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("อนุญาตการใช้แบต")
                .setMessage("เพื่อให้เซิร์ฟเวอร์ทำงานต่อเมื่อออกจากแอปหรือปิดหน้าจอ กรุณาอนุญาตการใช้แบตแบบไม่จำกัด")
                .setPositiveButton("เปิด", (dialog, which) -> requestUnlimitedBatteryAccess())
                .setNegativeButton("เปิดภายหลัง", null)
                .show();
    }

    private void requestUnlimitedBatteryAccess() {
        try {
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())
            ));
        } catch (Exception ignored) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())
                ));
            } catch (Exception error) {
                Toast.makeText(this, "ไม่สามารถเปิดการตั้งค่าแบตเตอรี่ได้", Toast.LENGTH_LONG).show();
            }
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
            logView.setText(value.isEmpty() ? "ยังไม่มี Log" : value);
            if (logScroll != null) {
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        if (requestCode == REQUEST_EXPORT_LOG) {
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
            return;
        }

        if (requestCode == REQUEST_EXPORT_ENDSTONE_PLUGIN) {
            downloadConfiguredEndstonePlugin(data.getData());
            return;
        }

        if (requestCode == REQUEST_EXPORT_ENDSTONE_CONFIG) {
            writeEndstoneConfig(data.getData());
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
        TextView title = text(titleText, 28, true);
        root.addView(title);

        TextView sub = text(subtitle, 14, false);
        sub.setTextColor(c(R.color.cyber_text_secondary));
        root.addView(sub, marginTop(5));
    }

    private LinearLayout pageRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(30));
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
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(rounded(
                c(R.color.cyber_surface),
                c(R.color.cyber_border),
                18,
                1
        ));
        layout.setElevation(dp(1));
        return layout;
    }

    private TextView eyebrow(String value) {
        TextView view = text(value, 15, true);
        view.setTextColor(c(R.color.cyber_text));
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
                12,
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
        button.setTextColor(Color.WHITE);
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
        button.setElevation(dp(1));
        attachPressAnimation(button);
        return button;
    }

    private void attachPressAnimation(Button button) {
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                button.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .alpha(0.90f)
                        .setDuration(90)
                        .start();
                button.setElevation(dp(1));
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                button.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(140)
                        .start();
                button.setElevation(dp(1));
            }
            return false;
        });
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
