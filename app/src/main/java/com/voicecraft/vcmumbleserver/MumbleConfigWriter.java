package com.voicecraft.vcmumbleserver;

import android.content.Context;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class MumbleConfigWriter {
    private MumbleConfigWriter() {}

    public static File write(Context context, ServerConfig config) throws IOException {
        File root = new File(context.getFilesDir(), "mumble");
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Unable to create Mumble data directory");
        }

        File database = new File(root, "mumble-server.sqlite");
        File ini = new File(root, "mumble-server.ini");
        String welcomeName = html(config.serverName);

        StringBuilder text = new StringBuilder();
        text.append("database=").append(quote(database.getAbsolutePath())).append('\n');
        text.append("logfile=\n");
        text.append("pidfile=\n");
        text.append("host=0.0.0.0\n");
        text.append("port=").append(config.port).append('\n');
        text.append("users=").append(config.maxUsers).append('\n');
        text.append("serverpassword=").append(quote(config.password)).append('\n');
        text.append("welcometext=").append(quote("<b>" + welcomeName + "</b><br>Hosted by VC Mumble Server")).append('\n');
        text.append("allowping=true\n");
        text.append("bonjour=false\n");

        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(ini, false)) {
            output.write(bytes);
            output.flush();
        }
        return ini;
    }

    private static String quote(String value) {
        if (value == null) value = "";
        value = value.replace("\r", " ").replace("\n", " ");
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String html(String value) {
        if (TextUtils.isEmpty(value)) return "Minecraft Voice";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
