package com.voicecraft.vcmumbleserver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class McsvInstaller {
    private static final int MAX_MCSV_UPLOAD_BYTES = 13 * 1024 * 1024;

    private McsvInstaller() {}

    static InstallResult install(
            String apiKey,
            EndstoneReleaseResolver.ReleaseInfo release,
            String bridgeSecret,
            int preferredBridgePort,
            int voiceRange
    ) throws IOException {
        McsvApiClient client = new McsvApiClient(apiKey);
        client.validateKey();

        JSONObject server = client.callTool("server_info", new JSONObject());
        String game = server.optString("game", "");
        String serverType = server.optString("server_type", "");
        if (!"minecraft-bedrock".equalsIgnoreCase(game)) {
            throw new IOException("API Key นี้ไม่ได้ผูกกับเซิร์ฟเวอร์ Minecraft Bedrock");
        }
        if (!"endstone".equalsIgnoreCase(serverType)) {
            throw new IOException(
                    "เซิร์ฟเวอร์นี้เป็น " + (serverType.isEmpty() ? "ชนิดที่ไม่รู้จัก" : serverType)
                            + " — ต้องใช้เซิร์ฟเวอร์ Endstone"
            );
        }

        int primaryPort = server.optInt("port", -1);
        int bridgePort = chooseBridgePort(
                server.optJSONArray("ports"),
                primaryPort,
                preferredBridgePort
        );

        JSONObject domain = client.callTool("domain_info", new JSONObject());
        String bridgeHost = firstNonEmpty(
                domain.optString("full_domain", ""),
                domain.optString("node_hostname", ""),
                normalizeHost(domain.optString("connect_address", ""))
        );
        if (bridgeHost.isEmpty()) {
            throw new IOException("MCSV API ไม่ได้ส่ง Host สำหรับเชื่อมต่อกลับมา");
        }

        EndstonePluginPackage.ConfiguredPlugin plugin =
                EndstonePluginPackage.downloadAndConfigure(release, bridgeSecret);
        if (plugin.bytes.length > MAX_MCSV_UPLOAD_BYTES) {
            throw new IOException("ไฟล์ Endstone Plugin ใหญ่เกินขนาดที่ MCSV API อัปโหลดได้");
        }

        JSONObject pluginListing = client.callTool(
                "files_list",
                argument("directory", "/plugins")
        );
        List<String> oldWheels = findVcMumbleWheels(pluginListing.optJSONArray("files"));
        oldWheels.remove(release.wheelName);

        JSONObject uploadArgs = new JSONObject();
        try {
            uploadArgs.put("path", "/plugins/" + release.wheelName);
            uploadArgs.put(
                    "content_base64",
                    Base64.getEncoder().encodeToString(plugin.bytes)
            );
        } catch (JSONException impossible) {
            throw new IOException("Could not prepare MCSV plugin upload", impossible);
        }
        client.callTool("files_upload_base64", uploadArgs);

        if (!oldWheels.isEmpty()) {
            deletePluginFiles(client, oldWheels);
        }

        String config = EndstonePluginPackage.buildConfigToml(
                bridgeSecret,
                bridgePort,
                voiceRange
        );
        JSONObject configArgs = new JSONObject();
        try {
            configArgs.put("path", "/plugins/vc_mumble/config.toml");
            configArgs.put("content", config);
            configArgs.put("force_new", true);
        } catch (JSONException impossible) {
            throw new IOException("Could not prepare MCSV config", impossible);
        }
        client.callTool("files_write", configArgs);

        client.callTool(
                "power_action",
                argument("action", "restart")
        );

        return new InstallResult(
                server.optString("name", "MCSV Server"),
                bridgeHost,
                bridgePort,
                release.tagName,
                release.wheelName,
                plugin.sourceSha256
        );
    }

    static int chooseBridgePort(
            JSONArray ports,
            int primaryPort,
            int preferredBridgePort
    ) throws IOException {
        List<Integer> available = new ArrayList<>();
        if (ports != null) {
            for (int i = 0; i < ports.length(); i++) {
                int value = ports.optInt(i, -1);
                if (value >= 1 && value <= 65535 && !available.contains(value)) {
                    available.add(value);
                }
            }
        }

        if (preferredBridgePort >= 1
                && preferredBridgePort <= 65535
                && preferredBridgePort != primaryPort
                && available.contains(preferredBridgePort)) {
            return preferredBridgePort;
        }

        for (int port : available) {
            if (port != primaryPort) {
                return port;
            }
        }

        throw new IOException(
                "เซิร์ฟเวอร์ MCSV ไม่มี Port ว่างสำหรับ VC Mumble Bridge "
                        + "กรุณาเพิ่ม Port ให้เซิร์ฟเวอร์ก่อน"
        );
    }

    private static List<String> findVcMumbleWheels(JSONArray files) {
        List<String> result = new ArrayList<>();
        if (files == null) return result;

        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null || !file.optBoolean("is_file", false)) continue;
            String name = file.optString("name", "");
            if (name.startsWith("endstone_vc_mumble-") && name.endsWith(".whl")) {
                result.add(name);
            }
        }
        return result;
    }

    private static void deletePluginFiles(
            McsvApiClient client,
            List<String> files
    ) throws IOException {
        if (files == null || files.isEmpty()) return;

        JSONArray names = new JSONArray();
        for (String file : files) {
            names.put(file);
        }

        JSONObject args = new JSONObject();
        try {
            args.put("root", "/plugins");
            args.put("files", names);
        } catch (JSONException impossible) {
            throw new IOException("Could not prepare old plugin cleanup", impossible);
        }
        client.callTool("files_delete", args);
    }

    private static JSONObject argument(String key, Object value) throws IOException {
        JSONObject args = new JSONObject();
        try {
            args.put(key, value);
            return args;
        } catch (JSONException impossible) {
            throw new IOException("Could not prepare MCSV request", impossible);
        }
    }

    private static String normalizeHost(String value) {
        if (value == null) return "";
        String host = value.trim();
        int scheme = host.indexOf("://");
        if (scheme >= 0) host = host.substring(scheme + 3);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close > 1) return host.substring(1, close);
        }
        int firstColon = host.indexOf(':');
        int lastColon = host.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            String portPart = host.substring(firstColon + 1);
            if (portPart.matches("[0-9]{1,5}")) {
                return host.substring(0, firstColon);
            }
        }
        return host;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    static final class InstallResult {
        final String serverName;
        final String bridgeHost;
        final int bridgePort;
        final String releaseTag;
        final String wheelName;
        final String sourceSha256;

        InstallResult(
                String serverName,
                String bridgeHost,
                int bridgePort,
                String releaseTag,
                String wheelName,
                String sourceSha256
        ) {
            this.serverName = serverName;
            this.bridgeHost = bridgeHost;
            this.bridgePort = bridgePort;
            this.releaseTag = releaseTag;
            this.wheelName = wheelName;
            this.sourceSha256 = sourceSha256;
        }
    }
}
