package com.voicecraft.vcmumbleserver;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Verifies that playit executables installed as APK native payloads can be
 * executed by Android. This intentionally does not claim an account or start a
 * tunnel yet; it only proves the packaged-code execution path.
 */
public final class PlayitEmbeddedProbe {
    public interface Callback {
        void onComplete(boolean success, String message);
    }

    private static final long TIMEOUT_SECONDS = 12;

    private PlayitEmbeddedProbe() {}

    public static void run(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean success = false;
            String result;
            try {
                if (!BuildConfig.VC_EMBEDDED_PLAYIT) {
                    throw new IllegalStateException("This APK was built without embedded playit");
                }

                ApplicationInfo info = app.getApplicationInfo();
                File nativeDir = new File(info.nativeLibraryDir);
                File cli = new File(nativeDir, "libplayit_cli_exec.so");
                File daemon = new File(nativeDir, "libplayitd_exec.so");

                logCandidate(app, "playit-cli", cli);
                logCandidate(app, "playitd", daemon);

                String cliOutput = runCommand(app, cli, "version");
                String daemonOutput = runCommand(app, daemon, "--help");

                success = true;
                result = "Embedded playit executable test passed. CLI=" + compact(cliOutput)
                        + "; daemon=" + firstLine(daemonOutput);
                ServerLog.append(app, "PLAYIT", result);
            } catch (Throwable error) {
                result = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                ServerLog.append(app, "PLAYIT", "Embedded execution FAILED: " + result);
            }

            final boolean finalSuccess = success;
            final String finalResult = result;
            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).runOnUiThread(
                        () -> callback.onComplete(finalSuccess, finalResult)
                );
            } else {
                callback.onComplete(finalSuccess, finalResult);
            }
        }, "Playit-Embedded-Probe").start();
    }

    private static void logCandidate(Context context, String label, File file) {
        ServerLog.append(
                context,
                "PLAYIT",
                label + " path=" + file.getAbsolutePath()
                        + ", exists=" + file.isFile()
                        + ", bytes=" + (file.isFile() ? file.length() : -1)
                        + ", canExecute=" + file.canExecute()
        );
    }

    private static String runCommand(Context context, File executable, String... args)
            throws Exception {
        if (!executable.isFile()) {
            throw new IOException("Missing packaged executable: " + executable.getName());
        }

        String[] command = new String[args.length + 1];
        command[0] = executable.getAbsolutePath();
        System.arraycopy(args, 0, command, 1, args.length);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.directory(context.getFilesDir());
        builder.environment().put("HOME", context.getFilesDir().getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());

        ServerLog.append(context, "PLAYIT",
                "exec " + executable.getName() + " " + String.join(" ", args));

        final Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new IOException(
                    "Android exec failed for packaged " + executable.getName()
                            + ": " + error.getMessage(),
                    error
            );
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count <= 0) continue;
                    int room = 64 * 1024 - output.size();
                    if (room <= 0) continue;
                    output.write(buffer, 0, Math.min(count, room));
                }
            } catch (IOException ignored) {
            }
        }, "Playit-Embedded-Probe-Output");
        reader.start();

        boolean exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            throw new IOException(executable.getName() + " timed out");
        }
        reader.join(1000);

        int exit = process.exitValue();
        String text = output.toString(StandardCharsets.UTF_8.name()).trim();
        ServerLog.append(context, "PLAYIT",
                executable.getName() + " exit=" + exit + ", output=" + compact(text));

        if (exit != 0) {
            throw new IOException(
                    executable.getName() + " exited " + exit + ": " + compact(text)
            );
        }
        return text;
    }

    private static String compact(String value) {
        if (value == null) return "";
        String compact = value.replace("\r", " ").replace("\n", " | ").trim();
        if (compact.length() > 1200) compact = compact.substring(0, 1200) + "…";
        return compact;
    }

    private static String firstLine(String value) {
        if (value == null || value.isEmpty()) return "<no output>";
        int newline = value.indexOf('\n');
        String line = newline >= 0 ? value.substring(0, newline) : value;
        return compact(line);
    }
}
