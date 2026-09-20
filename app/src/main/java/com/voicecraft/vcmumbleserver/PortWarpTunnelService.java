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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Foreground owner for the official PortWarp CLI running inside app-private
 * storage. The CLI keeps its login and daemon state under an isolated HOME.
 *
 * PortWarp Free supports one TCP/UDP tunnel. Tunnel creation itself remains a
 * dashboard operation in the official product, so this service handles device
 * login plus connect/status/stop for tunnels already configured there.
 */
public final class PortWarpTunnelService extends Service {
    public static final String ACTION_LOGIN = "com.voicecraft.vcmumbleserver.PORTWARP_LOGIN";
    public static final String ACTION_START = "com.voicecraft.vcmumbleserver.PORTWARP_START";
    public static final String ACTION_STOP = "com.voicecraft.vcmumbleserver.PORTWARP_STOP";
    public static final String ACTION_STATUS = "com.voicecraft.vcmumbleserver.PORTWARP_STATUS";
    public static final String ACTION_STATE = "com.voicecraft.vcmumbleserver.PORTWARP_STATE";

    public static final String EXTRA_ACTIVE = "active";
    public static final String EXTRA_CONFIGURED = "configured";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ENDPOINT = "endpoint";
    public static final String EXTRA_LOGIN_URL = "login_url";

    private static final String PREFS = "vc_portwarp";
    private static final String PREF_CONFIGURED = "configured";
    private static final String PREF_ENDPOINT = "endpoint";

    private static final String CHANNEL_ID = "vc_mumble_portwarp";
    private static final int NOTIFICATION_ID = 4120;

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s<>\"']+");
    private static final Pattern ENDPOINT_PATTERN =
            Pattern.compile("(?i)([a-z0-9][a-z0-9.-]*\\.pwrp\\.cc(?::[0-9]{1,5})?)");

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Process loginProcess;
    private volatile boolean stopping;
    private File cli;
    private File launcher;
    private File root;
    private File home;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        root = PortWarpExecProbe.rootDir(this);
        home = PortWarpExecProbe.homeDir(this);
        ServerLog.append(this, "PORTWARP", "Tunnel service created; runtime="
                + root.getAbsolutePath());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STATUS : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopping = true;
            Process pending = loginProcess;
            loginProcess = null;
            destroyProcess(pending);
            enterForeground("Stopping PortWarp…");
            worker.execute(() -> stopTunnel(true));
            return START_NOT_STICKY;
        }

        if (ACTION_LOGIN.equals(action)) {
            stopping = false;
            enterForeground("Preparing PortWarp login…");
            worker.execute(this::login);
            return START_STICKY;
        }

        if (ACTION_START.equals(action)) {
            stopping = false;
            enterForeground("Starting PortWarp tunnel…");
            worker.execute(this::startTunnel);
            return START_STICKY;
        }

        worker.execute(() -> status(startId));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        destroyProcess(loginProcess);
        loginProcess = null;
        worker.shutdownNow();
        ServerLog.append(this, "PORTWARP", "Tunnel service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void login() {
        try {
            requireRuntime();
            publish(false, false, "PortWarp login starting", loadEndpoint(), "");

            ProcessBuilder builder = command("login");
            builder.redirectErrorStream(true);
            // The Android app owns browser launch from URLs emitted by the CLI.
            // A missing desktop opener is expected and must not affect approval.
            builder.environment().put("BROWSER", "/system/bin/false");

            Process process = builder.start();
            loginProcess = process;

            ByteArrayOutputStream all = new ByteArrayOutputStream();
            String lastUrl = "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (all.size() < 128 * 1024) {
                        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                        all.write(bytes, 0, Math.min(bytes.length, 128 * 1024 - all.size()));
                    }
                    String clean = compact(line);
                    if (clean.isEmpty()) continue;
                    Matcher url = URL_PATTERN.matcher(clean);
                    if (url.find()) {
                        lastUrl = trimUrl(url.group());
                        publish(false, false,
                                "Approve this device in PortWarp, then return to the app",
                                loadEndpoint(), lastUrl);
                    } else {
                        publish(false, false, "PortWarp login: " + clean,
                                loadEndpoint(), lastUrl);
                    }
                }
            }

            int exit = process.waitFor();
            if (process == loginProcess) loginProcess = null;
            String output = all.toString(StandardCharsets.UTF_8.name());
            ServerLog.append(this, "PORTWARP", "pwrp login exit=" + exit
                    + ", output=" + redact(compact(output)));
            if (stopping) return;
            if (exit != 0) {
                throw new IOException("pwrp login exited " + exit + ": "
                        + redact(compact(output)));
            }

            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(PREF_CONFIGURED, true).commit();
            publish(false, true,
                    "PortWarp login approved • create/verify the TCP+UDP tunnel, then start public access",
                    loadEndpoint(), "");
            updateNotification("PortWarp configured");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } catch (Throwable error) {
            if (!stopping) fail("PortWarp login failed", error);
        }
    }

    private void startTunnel() {
        try {
            requireRuntime();

            CommandResult status = run(12, false, "status");
            if (status.exitCode != 0 || looksLoggedOut(status.output)) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putBoolean(PREF_CONFIGURED, false).commit();
                throw new IllegalStateException("PortWarp is not logged in. Run SET UP PORTWARP first.");
            }
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(PREF_CONFIGURED, true).commit();

            int localPort = ServerConfig.load(this).port;
            CommandResult tunnels = run(15, false, "tunnels");
            if (tunnels.exitCode != 0) {
                throw new IOException("Unable to list PortWarp tunnels: "
                        + redact(compact(tunnels.output)));
            }

            String tunnelText = tunnels.output == null ? "" : tunnels.output;
            if (tunnelText.trim().isEmpty() || looksNoTunnel(tunnelText)) {
                publish(false, true,
                        "No PortWarp tunnel found • open PortWarp Tunnels and create TCP+UDP → 127.0.0.1:"
                                + localPort,
                        "", "https://portwarp.com/tunnels");
                throw new NonRetryableException("No PortWarp tunnel is configured");
            }
            if (!tunnelText.contains(String.valueOf(localPort))) {
                publish(false, true,
                        "PortWarp tunnel local port does not appear to match Mumble port " + localPort,
                        extractEndpoint(tunnelText), "https://portwarp.com/tunnels");
                throw new NonRetryableException(
                        "Configure the PortWarp tunnel local port as " + localPort);
            }

            ServerLog.append(this, "PORTWARP",
                    "Connecting enabled PortWarp tunnel(s) to local Mumble port " + localPort);
            CommandResult connect = run(30, true,
                    "connect", "--all", "--save", "--detach");
            if (connect.exitCode != 0) {
                throw new IOException("pwrp connect exited " + connect.exitCode + ": "
                        + redact(compact(connect.output)));
            }

            Thread.sleep(1200L);
            CommandResult sessions = run(15, false, "ps", "--once");
            String combined = tunnelText + "\n" + connect.output + "\n" + sessions.output;
            String endpoint = extractEndpoint(combined);
            if (!endpoint.isEmpty()) saveEndpoint(endpoint);

            publish(true, true,
                    endpoint.isEmpty()
                            ? "PortWarp tunnel online • public endpoint available in PortWarp dashboard"
                            : "PortWarp tunnel online",
                    endpoint, "");
            updateNotification(endpoint.isEmpty()
                    ? "PortWarp public access online"
                    : "Public: " + endpoint);

            // Keep this process foreground while pwrp's background daemon owns
            // the sockets. Android then treats the app as actively serving.
        } catch (NonRetryableException error) {
            ServerLog.append(this, "PORTWARP", error.getMessage());
            updateNotification("PortWarp setup required");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } catch (Throwable error) {
            if (!stopping) fail("PortWarp start failed", error);
        }
    }

    private void stopTunnel(boolean stopService) {
        try {
            requireRuntime();
            run(12, true, "stop", "--all");
        } catch (Throwable error) {
            ServerLog.append(this, "PORTWARP", "Stop warning: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }

        publish(false, isConfigured(),
                isConfigured() ? "PortWarp configured • public access stopped" : "PortWarp stopped",
                loadEndpoint(), "");
        ServerLog.append(this, "PORTWARP", "Public access stopped");
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (stopService) stopSelf();
    }

    private void status(int startId) {
        boolean configured = isConfigured();
        boolean active = false;
        String endpoint = loadEndpoint();
        String message;

        try {
            requireRuntime();
            CommandResult status = run(8, false, "status");
            configured = status.exitCode == 0 && !looksLoggedOut(status.output);
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(PREF_CONFIGURED, configured).commit();

            if (configured) {
                CommandResult sessions = run(8, false, "ps", "--once");
                String text = sessions.output == null ? "" : sessions.output;
                active = sessions.exitCode == 0 && looksActive(text);
                String detected = extractEndpoint(text);
                if (!detected.isEmpty()) {
                    endpoint = detected;
                    saveEndpoint(endpoint);
                }
            }
            message = configured
                    ? (active ? "PortWarp public access online"
                              : "PortWarp configured • public access stopped")
                    : "PortWarp not configured";
        } catch (Throwable error) {
            message = configured
                    ? "PortWarp configured • status check unavailable"
                    : "PortWarp not configured";
            ServerLog.append(this, "PORTWARP", "Status check: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }

        publish(active, configured, message, endpoint, "");
        if (!active) stopSelf(startId);
    }

    private void requireRuntime() throws Exception {
        cli = PortWarpExecProbe.ensureInstalled(this);
        launcher = PortWarpExecProbe.launcherFile(this);
        root = PortWarpExecProbe.rootDir(this);
        home = PortWarpExecProbe.homeDir(this);
        if (!cli.isFile() || !cli.canExecute()) {
            throw new IOException("PortWarp CLI is unavailable after installation");
        }
        if (!launcher.isFile() || !launcher.canExecute()) {
            throw new IOException("PortWarp DNS launcher is unavailable");
        }
    }

    private CommandResult run(int timeoutSeconds, boolean logOutput, String... args)
            throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add(cli.getAbsolutePath());
        for (String arg : args) command.add(arg);

        ServerLog.append(this, "PORTWARP", "exec pwrp " + String.join(" ", args));
        Process process = command(args).start();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copyOutput(process, output), "PortWarp-Command-Output");
        reader.setDaemon(true);
        reader.start();

        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            destroyProcess(process);
            throw new IOException("pwrp " + (args.length == 0 ? "" : args[0])
                    + " timed out after " + timeoutSeconds + " seconds");
        }
        reader.join(1000L);

        int exit = process.exitValue();
        String text = output.toString(StandardCharsets.UTF_8.name()).trim();
        if (logOutput) {
            ServerLog.append(this, "PORTWARP", "pwrp exit=" + exit
                    + ", output=" + redact(compact(text)));
        } else {
            ServerLog.append(this, "PORTWARP", "pwrp exit=" + exit
                    + ", output=<suppressed>");
        }
        return new CommandResult(exit, text);
    }

    private ProcessBuilder command(String... args) throws IOException {
        File resolver = PortWarpExecProbe.prepareResolverFile(this);

        ArrayList<String> command = new ArrayList<>();
        command.add(launcher.getAbsolutePath());
        command.add(resolver.getAbsolutePath());
        command.add(cli.getAbsolutePath());
        for (String arg : args) command.add(arg);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.directory(root);
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
        return builder;
    }

    private void copyOutput(Process process, ByteArrayOutputStream output) {
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count <= 0) continue;
                int room = 128 * 1024 - output.size();
                if (room <= 0) continue;
                output.write(buffer, 0, Math.min(count, room));
            }
        } catch (IOException ignored) {
        }
    }

    private void fail(String prefix, Throwable error) {
        String message = prefix + ": " + error.getClass().getSimpleName()
                + ": " + String.valueOf(error.getMessage());
        ServerLog.append(this, "PORTWARP", message);
        publish(false, isConfigured(), message, loadEndpoint(), "");
        updateNotification("PortWarp error");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void publish(boolean active, boolean configured, String status,
                         String endpoint, String loginUrl) {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra(EXTRA_ACTIVE, active);
        state.putExtra(EXTRA_CONFIGURED, configured);
        state.putExtra(EXTRA_STATUS, status == null ? "" : status);
        state.putExtra(EXTRA_ENDPOINT, endpoint == null ? "" : endpoint);
        state.putExtra(EXTRA_LOGIN_URL, loginUrl == null ? "" : loginUrl);
        sendBroadcast(state);
    }

    private boolean isConfigured() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_CONFIGURED, false);
    }

    private void saveEndpoint(String endpoint) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(PREF_ENDPOINT, endpoint).commit();
    }

    private String loadEndpoint() {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_ENDPOINT, "");
        return value == null ? "" : value;
    }

    private static boolean looksLoggedOut(String output) {
        String v = output == null ? "" : output.toLowerCase();
        return v.contains("not logged") || v.contains("not signed")
                || v.contains("run pwrp login") || v.contains("please login");
    }

    private static boolean looksNoTunnel(String output) {
        String v = output == null ? "" : output.toLowerCase();
        return v.contains("no tunnels") || v.contains("0 tunnel")
                || v.contains("create your first");
    }

    private static boolean looksActive(String output) {
        String v = output == null ? "" : output.toLowerCase();
        return !(v.trim().isEmpty() || v.contains("no running")
                || v.contains("0 session") || v.contains("not running"));
    }

    private static String extractEndpoint(String output) {
        Matcher matcher = ENDPOINT_PATTERN.matcher(output == null ? "" : output);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String trimUrl(String url) {
        if (url == null) return "";
        while (url.endsWith(".") || url.endsWith(",") || url.endsWith(")")
                || url.endsWith("]")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String compact(String value) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (clean.length() > 1800) clean = clean.substring(0, 1800) + "…";
        return clean;
    }

    private static String redact(String value) {
        if (value == null) return "";
        return value
                .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "<email>")
                .replaceAll("(?i)(token|secret|key)[=: ]+[^ ]+", "$1=<redacted>");
    }

    private void enterForeground(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                this, 20, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, PortWarpTunnelService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(
                this, 21, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("VC Mumble • PortWarp")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopAction).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "PortWarp public access", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the PortWarp tunnel for VC Mumble running");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private static void destroyProcess(Process process) {
        if (process == null) return;
        try {
            process.destroy();
            if (!process.waitFor(1200L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(800L, TimeUnit.MILLISECONDS);
            }
        } catch (Exception ignored) {
            try {
                process.destroyForcibly();
            } catch (Exception ignoredAgain) {
            }
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    private static final class NonRetryableException extends IOException {
        NonRetryableException(String message) {
            super(message);
        }
    }
}
