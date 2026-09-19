package com.voicecraft.vcmumbleserver;

import android.content.Context;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Persistent app-private log shared by the Android service and embedded Mumble. */
public final class ServerLog {
    public static final String FILE_NAME = "vc-mumble-server.log";
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final int UI_MAX_BYTES = 96 * 1024;

    private ServerLog() {}

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static synchronized void append(Context context, String component, String message) {
        if (context == null) return;
        String safeComponent = component == null || component.isEmpty() ? "APP" : component;
        String safeMessage = message == null ? "" : message.replace("\r", " ").replace("\n", " ");
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = "[" + timestamp + "][" + safeComponent + "] " + safeMessage + "\n";
        File target = file(context);

        try {
            trimIfNeeded(target);
            try (FileOutputStream output = new FileOutputStream(target, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (IOException ignored) {
            // Logging must never crash the server.
        }
    }

    public static synchronized String read(Context context) {
        File target = file(context);
        if (!target.isFile()) return "";

        try (FileInputStream input = new FileInputStream(target)) {
            long length = target.length();
            long skip = Math.max(0L, length - UI_MAX_BYTES);
            while (skip > 0) {
                long skipped = input.skip(skip);
                if (skipped <= 0) break;
                skip -= skipped;
            }
            byte[] data = readRemaining(input);
            String text = new String(data, StandardCharsets.UTF_8);
            if (length > UI_MAX_BYTES) {
                int firstNewline = text.indexOf('\n');
                if (firstNewline >= 0 && firstNewline + 1 < text.length()) {
                    text = text.substring(firstNewline + 1);
                }
                return "[... older log lines omitted from viewer ...]\n" + text;
            }
            return text;
        } catch (IOException error) {
            return "Unable to read log: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    public static synchronized byte[] readAllBytes(Context context) throws IOException {
        File target = file(context);
        if (!target.isFile()) return new byte[0];
        try (FileInputStream input = new FileInputStream(target)) {
            return readRemaining(input);
        }
    }

    public static synchronized String readAll(Context context) {
        try {
            return new String(readAllBytes(context), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "Unable to read log: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    public static synchronized void clear(Context context) {
        File target = file(context);
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
        append(context, "APP", "Log cleared");
    }

    private static byte[] readRemaining(FileInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void trimIfNeeded(File target) throws IOException {
        if (!target.isFile() || target.length() <= MAX_BYTES) return;

        byte[] data;
        try (FileInputStream input = new FileInputStream(target)) {
            data = readRemaining(input);
        }

        int keepFrom = Math.max(0, data.length - (int) (MAX_BYTES / 2));
        while (keepFrom < data.length && data[keepFrom] != '\n') keepFrom++;
        if (keepFrom < data.length) keepFrom++;

        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(("[log rotated at " + new Date() + "]\n").getBytes(StandardCharsets.UTF_8));
            if (keepFrom < data.length) {
                output.write(data, keepFrom, data.length - keepFrom);
            }
        }
    }
}
