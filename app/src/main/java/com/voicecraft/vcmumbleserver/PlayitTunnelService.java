package com.voicecraft.vcmumbleserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlayitTunnelService extends Service {
    public static final String ACTION_CLAIM = "com.voicecraft.vcmumbleserver.PLAYIT_CLAIM";
    public static final String ACTION_START = "com.voicecraft.vcmumbleserver.PLAYIT_START";
    public static final String ACTION_STOP = "com.voicecraft.vcmumbleserver.PLAYIT_STOP";
    public static final String ACTION_STATUS = "com.voicecraft.vcmumbleserver.PLAYIT_STATUS";
    public static final String ACTION_STATE = "com.voicecraft.vcmumbleserver.PLAYIT_STATE";

    public static final String EXTRA_CLAIM_CODE = "claim_code";
    public static final String EXTRA_ACTIVE = "active";
    public static final String EXTRA_CONFIGURED = "configured";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ENDPOINT = "endpoint";
    public static final String EXTRA_CLAIM_URL = "claim_url";

    private static final String PLAYIT_PREFS = "vc_playit";
    private static final String PREF_ENDPOINT = "public_endpoint";
    private static final String PREF_TUNNEL_ID = "tunnel_id";

    private static final String CHANNEL_ID = "vc_mumble_public_access";
    private static final int NOTIFICATION_ID = 4110;
    private static final Pattern HEX_SECRET = Pattern.compile("(?i)([0-9a-f]{32,})");

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile Process claimProcess;
    private volatile Process daemonProcess;
    private volatile boolean stopping;

    private File cli;
    private File daemon;
    private File helper;
    private File playitDir;
    private File socketFile;
    private File secretFile;
    private File daemonLog;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        ApplicationInfo info = getApplicationInfo();
        File nativeDir = new File(info.nativeLibraryDir);
        cli = new File(nativeDir, "libplayit_cli_exec.so");
        daemon = new File(nativeDir, "libplayitd_exec.so");
        helper = new File(nativeDir, "libvc_playit_helper_exec.so");

        playitDir = new File(getFilesDir(), "playit");
        socketFile = new File(playitDir, "playit.sock");
        secretFile = new File(playitDir, "agent-secret.toml");
        daemonLog = new File(playitDir, "playitd.log");

        ServerLog.append(this, "PLAYIT", "Tunnel service created; nativeLibDir="
                + info.nativeLibraryDir);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STATUS : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            enterForeground("Stopping public access…");
            worker.execute(() -> stopPublicAccess(true));
            return START_NOT_STICKY;
        }

        if (ACTION_CLAIM.equals(action)) {
            String code = intent == null ? "" : intent.getStringExtra(EXTRA_CLAIM_CODE);
            if (code == null || !code.matches("(?i)[0-9a-f]{10}")) {
                publish(false, "Invalid Playit claim code", "", "");
                stopSelf(startId);
                return START_NOT_STICKY;
            }

            enterForeground("Waiting for Playit approval…");
            final String claimCode = code.toLowerCase(Locale.US);
            worker.execute(() -> claimAndStart(claimCode));
            return START_STICKY;
        }

        if (ACTION_START.equals(action)) {
            enterForeground("Starting public access…");
            worker.execute(this::startFromStoredSecret);
            return START_STICKY;
        }

        worker.execute(() -> {
            boolean active = daemonProcess != null && daemonProcess.isAlive();
            boolean configured = PlayitSecretStore.hasStored(this);
            String endpoint = loadEndpoint();
            String message;
            if (active) {
                message = endpoint.isEmpty() ? "Playit daemon online" : "Public access online";
            } else if (configured) {
                message = "Playit configured • public access stopped";
            } else {
                message = "Playit not configured";
            }
            publish(active, message, endpoint, "");
            if (!active) stopSelf(startId);
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        destroyProcess(claimProcess);
        destroyProcess(daemonProcess);
        claimProcess = null;
        daemonProcess = null;
        worker.shutdownNow();
        deleteRuntimeSecret();
        ServerLog.append(this, "PLAYIT", "Tunnel service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void claimAndStart(String code) {
        stopping = false;
        stopChildProcessesOnly();

        String claimUrl = "https://playit.gg/claim/" + code;
        publish(true, "Waiting for Playit approval", "", claimUrl);
        updateNotification("Open Playit and approve this device");

        try {
            requireExecutables();

            ServerLog.append(this, "PLAYIT", "Waiting for Playit claim approval; code=" + code);
            CommandResult exchange = runCommand(
                    cli,
                    list("claim", "exchange", code, "--wait", "0"),
                    null,
                    0,
                    false
            );
            if (stopping) return;
            if (exchange.exitCode != 0) {
                throw new IOException("claim exchange exited " + exchange.exitCode);
            }

            String secret = extractSecret(exchange.output);
            if (secret.isEmpty()) {
                throw new IOException("Playit claim completed but no agent secret was returned");
            }

            PlayitSecretStore.save(this, secret);
            ServerLog.append(this, "PLAYIT",
                    "Playit claim approved; encrypted agent secret saved in Android Keystore");
            startDaemonAndTunnel(secret);
        } catch (Throwable error) {
            if (!stopping) {
                fail("Playit setup failed", error);
            }
        }
    }

    private void startFromStoredSecret() {
        stopping = false;
        try {
            requireExecutables();
            String secret = PlayitSecretStore.load(this);
            if (!isHexSecret(secret)) {
                throw new IllegalStateException("Playit is not configured. Run setup first.");
            }
            startDaemonAndTunnel(secret);
        } catch (Throwable error) {
            if (!stopping) {
                fail("Public access start failed", error);
            }
        }
    }

    private void startDaemonAndTunnel(String secret) throws Exception {
        if (stopping) return;
        ensurePlayitDir();
        materializeSecret(secret);

        if (daemonProcess == null || !daemonProcess.isAlive()) {
            socketFile.delete();

            ProcessBuilder builder = new ProcessBuilder(
                    daemon.getAbsolutePath(),
                    "--secret-path", secretFile.getAbsolutePath(),
                    "--socket-path", socketFile.getAbsolutePath(),
                    "--log-path", daemonLog.getAbsolutePath()
            );
            builder.directory(playitDir);
            builder.redirectErrorStream(true);
            builder.environment().put("HOME", playitDir.getAbsolutePath());
            builder.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            builder.environment().put("PLAYIT_LOG", "info");

            ServerLog.append(this, "PLAYITD", "Starting embedded playitd; socket="
                    + socketFile.getAbsolutePath());
            daemonProcess = builder.start();
            drainDaemonOutput(daemonProcess);
        }

        updateNotification("Connecting Playit agent…");
        publish(true, "Connecting Playit agent", loadEndpoint(), "");

        waitForDaemonReady();

        if (stopping) return;
        ServerConfig config = ServerConfig.load(this);
        String endpoint = ensureMumbleTunnel(secret, config.port);
        if (!endpoint.isEmpty()) saveEndpoint(endpoint);

        String finalEndpoint = endpoint.isEmpty() ? loadEndpoint() : endpoint;
        String message = finalEndpoint.isEmpty()
                ? "Playit online • public endpoint pending"
                : "Public access online";
        publish(true, message, finalEndpoint, "");
        updateNotification(finalEndpoint.isEmpty()
                ? "Playit online • endpoint pending"
                : "Public • " + finalEndpoint);

        ServerLog.append(this, "PLAYIT",
                finalEndpoint.isEmpty()
                        ? "Embedded Playit is running; public allocation is still pending"
                        : "Public Mumble endpoint: " + finalEndpoint);
    }

    private void waitForDaemonReady() throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 30; attempt++) {
            if (stopping) throw new InterruptedException("Playit stop requested");
            if (daemonProcess == null || !daemonProcess.isAlive()) {
                int exit = daemonProcess == null ? -1 : daemonProcess.exitValue();
                throw new IOException("playitd exited before becoming ready: " + exit);
            }

            try {
                CommandResult status = runCommand(
                        cli,
                        list("--socket-path", socketFile.getAbsolutePath(), "status"),
                        null,
                        4,
                        true
                );
                if (status.exitCode == 0
                        && (status.output.contains("Phase:")
                        || status.output.contains("Waiting")
                        || status.output.contains("Running"))) {
                    ServerLog.append(this, "PLAYITD",
                            "Daemon IPC ready after " + attempt + " attempt(s)");
                    return;
                }
            } catch (Exception error) {
                last = error;
            }

            Thread.sleep(500L);
        }
        throw new IOException("playitd IPC did not become ready"
                + (last == null ? "" : ": " + last.getMessage()));
    }

    private String ensureMumbleTunnel(String secret, int localPort) throws Exception {
        HashMap<String, String> env = new HashMap<>();
        env.put("PLAYIT_SECRET_KEY", secret);

        Exception last = null;
        for (int attempt = 1; attempt <= 12; attempt++) {
            if (stopping) throw new InterruptedException("Playit stop requested");

            try {
                CommandResult result = runCommand(
                        helper,
                        list("ensure-mumble", String.valueOf(localPort)),
                        env,
                        20,
                        true
                );
                if (result.exitCode != 0) {
                    throw new IOException("tunnel helper exited " + result.exitCode
                            + ": " + compact(result.output));
                }

                JSONObject payload = new JSONObject(lastJsonLine(result.output));
                if (!payload.optBoolean("ok", false)) {
                    throw new IOException("Playit tunnel helper returned failure");
                }

                String tunnelId = payload.optString("tunnel_id", "");
                if (!tunnelId.isEmpty()) {
                    getSharedPreferences(PLAYIT_PREFS, MODE_PRIVATE)
                            .edit().putString(PREF_TUNNEL_ID, tunnelId).commit();
                }

                if (!payload.isNull("endpoint")) {
                    String endpoint = payload.optString("endpoint", "").trim();
                    if (!endpoint.isEmpty()) return endpoint;
                }

                publish(true,
                        "Playit online • waiting for public allocation ("
                                + attempt + "/12)",
                        "",
                        "");
            } catch (Exception error) {
                last = error;
                ServerLog.append(this, "PLAYIT",
                        "Tunnel provisioning attempt " + attempt + " failed: "
                                + error.getClass().getSimpleName() + ": "
                                + String.valueOf(error.getMessage()));
            }

            Thread.sleep(2000L);
        }

        if (last != null) throw last;
        return "";
    }

    private void stopPublicAccess(boolean stopService) {
        stopping = true;

        Process claim = claimProcess;
        claimProcess = null;
        destroyProcess(claim);

        Process daemonCurrent = daemonProcess;
        if (daemonCurrent != null && daemonCurrent.isAlive()) {
            try {
                runCommand(
                        cli,
                        list("--socket-path", socketFile.getAbsolutePath(), "stop"),
                        null,
                        5,
                        true
                );
            } catch (Exception error) {
                ServerLog.append(this, "PLAYIT",
                        "Graceful playitd stop failed: " + error.getClass().getSimpleName()
                                + ": " + String.valueOf(error.getMessage()));
            }
        }

        destroyProcess(daemonCurrent);
        daemonProcess = null;
        socketFile.delete();
        deleteRuntimeSecret();

        publish(false,
                PlayitSecretStore.hasStored(this)
                        ? "Playit configured • public access stopped"
                        : "Playit stopped",
                loadEndpoint(),
                "");
        ServerLog.append(this, "PLAYIT", "Public access stopped");

        stopForeground(STOP_FOREGROUND_REMOVE);
        if (stopService) stopSelf();
    }

    private void stopChildProcessesOnly() {
        destroyProcess(claimProcess);
        destroyProcess(daemonProcess);
        claimProcess = null;
        daemonProcess = null;
        socketFile.delete();
        deleteRuntimeSecret();
    }

    private void requireExecutables() {
        if (!BuildConfig.VC_EMBEDDED_PLAYIT) {
            throw new IllegalStateException("This APK was built without embedded Playit");
        }
        for (File file : new File[]{cli, daemon, helper}) {
            if (!file.isFile() || !file.canExecute()) {
                throw new IllegalStateException("Packaged executable unavailable: "
                        + file.getName());
            }
        }
    }

    private void ensurePlayitDir() throws IOException {
        if (!playitDir.isDirectory() && !playitDir.mkdirs()) {
            throw new IOException("Unable to create Playit runtime directory");
        }
    }

    private void materializeSecret(String secret) throws IOException {
        if (!isHexSecret(secret)) throw new IOException("Stored Playit secret is invalid");
        ensurePlayitDir();

        String content = "secret_key = \"" + secret + "\"\n";
        try (FileOutputStream output = new FileOutputStream(secretFile, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        secretFile.setReadable(false, false);
        secretFile.setWritable(false, false);
        secretFile.setExecutable(false, false);
        secretFile.setReadable(true, true);
        secretFile.setWritable(true, true);
    }

    private void deleteRuntimeSecret() {
        if (secretFile != null && secretFile.exists() && !secretFile.delete()) {
            ServerLog.append(this, "PLAYIT",
                    "Warning: unable to remove materialized Playit secret file");
        }
    }

    private CommandResult runCommand(
            File executable,
            List<String> args,
            Map<String, String> extraEnv,
            int timeoutSeconds,
            boolean logOutput
    ) throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(playitDir.isDirectory() ? playitDir : getFilesDir());
        builder.redirectErrorStream(true);
        builder.environment().put("HOME",
                playitDir.isDirectory() ? playitDir.getAbsolutePath() : getFilesDir().getAbsolutePath());
        builder.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
        if (extraEnv != null) builder.environment().putAll(extraEnv);

        ServerLog.append(this, "PLAYIT", "exec " + executable.getName() + " "
                + String.join(" ", args));

        Process process = builder.start();
        if (executable.equals(cli) && args.size() >= 2
                && "claim".equals(args.get(0)) && "exchange".equals(args.get(1))) {
            claimProcess = process;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copyProcessOutput(process, output),
                "Playit-Command-Output");
        reader.setDaemon(true);
        reader.start();

        boolean exited;
        if (timeoutSeconds <= 0) {
            process.waitFor();
            exited = true;
        } else {
            exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        }

        if (!exited) {
            destroyProcess(process);
            throw new IOException(executable.getName() + " timed out after "
                    + timeoutSeconds + " seconds");
        }
        reader.join(1000L);

        if (process == claimProcess) claimProcess = null;

        int exit = process.exitValue();
        String text = output.toString(StandardCharsets.UTF_8.name()).trim();
        if (logOutput) {
            ServerLog.append(this, "PLAYIT",
                    executable.getName() + " exit=" + exit + ", output=" + compact(text));
        } else {
            ServerLog.append(this, "PLAYIT",
                    executable.getName() + " exit=" + exit + ", output=<redacted>");
        }
        return new CommandResult(exit, text);
    }

    private void copyProcessOutput(Process process, ByteArrayOutputStream output) {
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

    private void drainDaemonOutput(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines < 500 && !stopping) {
                    String clean = compact(line);
                    if (!clean.isEmpty()) {
                        ServerLog.append(this, "PLAYITD", clean);
                        lines++;
                    }
                }
            } catch (IOException ignored) {
            }
        }, "Playit-Daemon-Output");
        thread.setDaemon(true);
        thread.start();
    }

    private static String extractSecret(String output) {
        Matcher matcher = HEX_SECRET.matcher(output == null ? "" : output);
        String secret = "";
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate.length() % 2 == 0) secret = candidate;
        }
        return secret;
    }

    private static boolean isHexSecret(String value) {
        return value != null && value.length() >= 32 && value.length() % 2 == 0
                && value.matches("(?i)[0-9a-f]+");
    }

    private static String lastJsonLine(String output) {
        if (output == null) return "{}";
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) return line;
        }
        return "{}";
    }

    private static String compact(String value) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (clean.length() > 1800) clean = clean.substring(0, 1800) + "…";
        return clean;
    }

    private void saveEndpoint(String endpoint) {
        getSharedPreferences(PLAYIT_PREFS, MODE_PRIVATE)
                .edit().putString(PREF_ENDPOINT, endpoint).commit();
    }

    private String loadEndpoint() {
        String value = getSharedPreferences(PLAYIT_PREFS, MODE_PRIVATE)
                .getString(PREF_ENDPOINT, "");
        return value == null ? "" : value;
    }

    private void fail(String prefix, Throwable error) {
        String message = prefix + ": " + error.getClass().getSimpleName()
                + ": " + String.valueOf(error.getMessage());
        ServerLog.append(this, "PLAYIT", message);
        publish(false, message, loadEndpoint(), "");
        updateNotification("Public access error");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void publish(boolean active, String status, String endpoint, String claimUrl) {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra(EXTRA_ACTIVE, active);
        state.putExtra(EXTRA_CONFIGURED, PlayitSecretStore.hasStored(this));
        state.putExtra(EXTRA_STATUS, status == null ? "" : status);
        state.putExtra(EXTRA_ENDPOINT, endpoint == null ? "" : endpoint);
        state.putExtra(EXTRA_CLAIM_URL, claimUrl == null ? "" : claimUrl);
        sendBroadcast(state);
    }

    private void enterForeground(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
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
                this,
                10,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stop = new Intent(this, PlayitTunnelService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(
                this,
                11,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("VC Mumble Public Access")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopAction).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Mumble public access",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps the embedded Playit tunnel running in the background");
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

    private static List<String> list(String... values) {
        ArrayList<String> result = new ArrayList<>(values.length);
        for (String value : values) result.add(value);
        return result;
    }

    public static String newClaimCode() {
        byte[] bytes = new byte[5];
        new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder(10);
        for (byte b : bytes) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value.toString();
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
