package com.voicecraft.vcmumbleserver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

final class McsvBedrockAddonInstaller {
    private static final int MAX_MCSV_UPLOAD_BYTES = 13 * 1024 * 1024;
    private static final String BP_FOLDER = "VC_Mumble_ItemMic_BP";
    private static final String RP_FOLDER = "VC_Mumble_ItemMic_RP";

    private McsvBedrockAddonInstaller() {}

    static InstallResult install(
            McsvApiClient client,
            AddonReleaseResolver.ReleaseInfo release
    ) throws IOException {
        VerifiedAddonPackage.DownloadedAddon addon =
                VerifiedAddonPackage.downloadAndVerify(release);
        if (addon.bytes.length > MAX_MCSV_UPLOAD_BYTES) {
            throw new IOException(
                    "Item Mic Addon ใหญ่เกินขนาดสำหรับติดตั้งอัตโนมัติผ่าน MCSV API"
            );
        }

        String stageName = ".vc_mumble_addon_stage_" + Long.toHexString(System.currentTimeMillis());
        String stagePath = "/" + stageName;
        try {
            uploadArchive(client, stagePath + "/addon.zip", addon.bytes);
            client.callTool(
                    "files_decompress",
                    object("root", stagePath, "file", "addon.zip")
            );
            bestEffortDelete(client, stagePath, "addon.zip");

            List<PackInfo> packs = new ArrayList<>();
            discoverPacks(client, stagePath, stagePath, 0, packs);

            PackInfo behavior = null;
            PackInfo resource = null;
            for (PackInfo pack : packs) {
                if (pack.kind == PackKind.BEHAVIOR && behavior == null) {
                    behavior = pack;
                } else if (pack.kind == PackKind.RESOURCE && resource == null) {
                    resource = pack;
                }
            }

            if (behavior == null || resource == null) {
                throw new IOException(
                        "Item Mic Addon ต้องมีทั้ง Behavior Pack และ Resource Pack"
                );
            }

            String serverProperties = readText(client, "/server.properties");
            String levelName = parseLevelName(serverProperties);
            String worldPath = resolveWorldPath(client, levelName);

            WorldPackState behaviorWorld = loadWorldPackState(
                    client,
                    worldPath,
                    "world_behavior_packs.json"
            );
            WorldPackState resourceWorld = loadWorldPackState(
                    client,
                    worldPath,
                    "world_resource_packs.json"
            );

            PackSwap behaviorSwap = null;
            PackSwap resourceSwap = null;
            try {
                behaviorSwap = installPackFolder(
                        client,
                        behavior,
                        "/behavior_packs/" + BP_FOLDER,
                        "behavior_packs/" + BP_FOLDER
                );
                resourceSwap = installPackFolder(
                        client,
                        resource,
                        "/resource_packs/" + RP_FOLDER,
                        "resource_packs/" + RP_FOLDER
                );

                writeWorldPackState(client, behaviorWorld, behavior);
                writeWorldPackState(client, resourceWorld, resource);

                cleanupPackBackup(client, behaviorSwap);
                cleanupPackBackup(client, resourceSwap);
            } catch (IOException error) {
                restoreWorldPackState(client, resourceWorld);
                restoreWorldPackState(client, behaviorWorld);
                rollbackPackSwap(client, resourceSwap);
                rollbackPackSwap(client, behaviorSwap);
                throw error;
            }

            return new InstallResult(
                    release.addonName,
                    addon.sha256,
                    levelName,
                    behavior.uuid,
                    resource.uuid
            );
        } finally {
            bestEffortDelete(client, "/", stageName);
        }
    }

    private static void uploadArchive(
            McsvApiClient client,
            String path,
            byte[] bytes
    ) throws IOException {
        JSONObject args = new JSONObject();
        try {
            args.put("path", path);
            args.put(
                    "content_base64",
                    Base64.getEncoder().encodeToString(bytes)
            );
        } catch (JSONException impossible) {
            throw new IOException("Could not prepare MCSV Addon upload", impossible);
        }
        client.callTool("files_upload_base64", args);
    }

    private static void discoverPacks(
            McsvApiClient client,
            String stageRoot,
            String directory,
            int depth,
            List<PackInfo> output
    ) throws IOException {
        if (depth > 5) {
            throw new IOException("Item Mic Addon มีโครงสร้างซ้อนลึกเกินไป");
        }

        JSONObject listing = client.callTool(
                "files_list",
                object("directory", directory)
        );
        JSONArray files = listing.optJSONArray("files");
        if (files == null) return;

        boolean hasManifest = false;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            if (file.optBoolean("is_file", false)
                    && "manifest.json".equals(file.optString("name", ""))) {
                hasManifest = true;
                break;
            }
        }

        if (hasManifest) {
            if (directory.equals(stageRoot)) {
                throw new IOException(
                        "รูปแบบ Addon ที่มี manifest อยู่ root โดยตรงยังไม่รองรับ"
                );
            }
            String manifestText = readText(client, directory + "/manifest.json");
            output.add(parsePackManifest(directory, manifestText));
            return;
        }

        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String name = file.optString("name", "");
            if (!isSafeName(name)) continue;

            if (file.optBoolean("is_file", false)) {
                if (isPackArchive(name)) {
                    String unpackName = ".vc_unpack_" + depth + "_" + i;
                    String unpackDir = directory + "/" + unpackName;
                    client.callTool(
                            "files_mkdir",
                            object("root", directory, "name", unpackName)
                    );
                    rename(
                            client,
                            stripLeadingSlash(directory + "/" + name),
                            stripLeadingSlash(unpackDir + "/pack.zip")
                    );
                    client.callTool(
                            "files_decompress",
                            object("root", unpackDir, "file", "pack.zip")
                    );
                    bestEffortDelete(client, unpackDir, "pack.zip");
                    discoverPacks(
                            client,
                            stageRoot,
                            unpackDir,
                            depth + 1,
                            output
                    );
                }
                continue;
            }

            discoverPacks(
                    client,
                    stageRoot,
                    directory + "/" + name,
                    depth + 1,
                    output
            );
        }
    }

    static boolean isPackArchive(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mcpack") || lower.endsWith(".zip");
    }

    static PackInfo parsePackManifest(
            String directory,
            String manifestText
    ) throws IOException {
        try {
            JSONObject manifest = new JSONObject(manifestText);
            JSONObject header = manifest.optJSONObject("header");
            if (header == null) {
                throw new IOException("manifest.json ไม่มี header");
            }

            String uuid = header.optString("uuid", "").trim();
            if (!uuid.matches(
                    "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
            )) {
                throw new IOException("manifest.json มี pack UUID ไม่ถูกต้อง");
            }

            JSONArray versionArray = header.optJSONArray("version");
            int[] version = parseVersion(versionArray);

            PackKind kind = null;
            JSONArray modules = manifest.optJSONArray("modules");
            if (modules != null) {
                for (int i = 0; i < modules.length(); i++) {
                    JSONObject module = modules.optJSONObject(i);
                    if (module == null) continue;
                    String type = module.optString("type", "").toLowerCase(Locale.US);
                    if ("resources".equals(type)) {
                        kind = PackKind.RESOURCE;
                    } else if ("data".equals(type) || "script".equals(type)) {
                        if (kind != PackKind.RESOURCE) {
                            kind = PackKind.BEHAVIOR;
                        }
                    }
                }
            }

            if (kind == null) {
                throw new IOException("manifest.json ไม่ระบุชนิด Behavior/Resource Pack");
            }
            return new PackInfo(directory, uuid, version, kind);
        } catch (JSONException error) {
            throw new IOException("อ่าน manifest.json ของ Item Mic Addon ไม่สำเร็จ", error);
        }
    }

    private static int[] parseVersion(JSONArray version) throws IOException {
        if (version == null || version.length() < 3) {
            throw new IOException("manifest.json ไม่มี version แบบ [major, minor, patch]");
        }
        int[] result = new int[3];
        for (int i = 0; i < 3; i++) {
            int value = version.optInt(i, -1);
            if (value < 0) {
                throw new IOException("manifest.json มี version ไม่ถูกต้อง");
            }
            result[i] = value;
        }
        return result;
    }

    static String parseLevelName(String serverProperties) throws IOException {
        if (serverProperties == null) {
            throw new IOException("อ่าน server.properties ไม่สำเร็จ");
        }
        for (String rawLine : serverProperties.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            if (!"level-name".equals(line.substring(0, equals).trim())) continue;

            String value = line.substring(equals + 1).trim();
            if (value.isEmpty()
                    || value.contains("/")
                    || value.contains("\\")
                    || value.equals(".")
                    || value.equals("..")) {
                throw new IOException("level-name ใน server.properties ไม่ปลอดภัย");
            }
            return value;
        }
        throw new IOException("ไม่พบ level-name ใน server.properties");
    }

    private static String resolveWorldPath(
            McsvApiClient client,
            String levelName
    ) throws IOException {
        JSONObject listing = client.callTool(
                "files_list",
                object("directory", "/worlds")
        );
        JSONArray files = listing.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null || file.optBoolean("is_file", false)) continue;
                if (levelName.equals(file.optString("name", ""))) {
                    return "/worlds/" + levelName;
                }
            }
        }
        throw new IOException(
                "ไม่พบ world ที่ใช้งานอยู่: " + levelName
        );
    }

    private static PackSwap installPackFolder(
            McsvApiClient client,
            PackInfo pack,
            String destinationPath,
            String destinationRelative
    ) throws IOException {
        String backupRelative = destinationRelative
                + ".backup_" + Long.toHexString(System.currentTimeMillis());

        boolean existing = pathExists(
                client,
                destinationPath.substring(0, destinationPath.lastIndexOf('/')),
                destinationPath.substring(destinationPath.lastIndexOf('/') + 1)
        );

        if (existing) {
            rename(client, destinationRelative, backupRelative);
        }

        boolean movedNew = false;
        try {
            rename(
                    client,
                    stripLeadingSlash(pack.directory),
                    destinationRelative
            );
            movedNew = true;
            return new PackSwap(
                    destinationRelative,
                    backupRelative,
                    existing
            );
        } finally {
            if (!movedNew && existing) {
                bestEffortRename(client, backupRelative, destinationRelative);
            }
        }
    }

    private static void cleanupPackBackup(
            McsvApiClient client,
            PackSwap swap
    ) {
        if (swap != null && swap.hadExisting) {
            bestEffortDelete(client, "/", swap.backupRelative);
        }
    }

    private static void rollbackPackSwap(
            McsvApiClient client,
            PackSwap swap
    ) {
        if (swap == null) return;
        bestEffortDelete(client, "/", swap.destinationRelative);
        if (swap.hadExisting) {
            bestEffortRename(
                    client,
                    swap.backupRelative,
                    swap.destinationRelative
            );
        }
    }

    private static WorldPackState loadWorldPackState(
            McsvApiClient client,
            String worldPath,
            String fileName
    ) throws IOException {
        boolean existed = pathExists(client, worldPath, fileName);
        String content = "";
        JSONArray entries = new JSONArray();

        if (existed) {
            content = readText(client, worldPath + "/" + fileName);
            String trimmed = content.trim();
            if (!trimmed.isEmpty()) {
                try {
                    entries = new JSONArray(trimmed);
                } catch (JSONException error) {
                    throw new IOException(
                            fileName + " ของ world มี JSON ไม่ถูกต้อง",
                            error
                    );
                }
            }
        }

        return new WorldPackState(
                worldPath,
                fileName,
                existed,
                content,
                entries
        );
    }

    private static void writeWorldPackState(
            McsvApiClient client,
            WorldPackState state,
            PackInfo pack
    ) throws IOException {
        JSONArray merged = mergeWorldPackConfig(state.entries, pack);
        JSONObject args = new JSONObject();
        try {
            args.put("path", state.path());
            args.put("content", merged.toString(2) + "\n");
            if (!state.existed) {
                args.put("force_new", true);
            }
        } catch (JSONException impossible) {
            throw new IOException("Could not prepare world pack config", impossible);
        }
        client.callTool("files_write", args);
        state.changed = true;
    }

    private static void restoreWorldPackState(
            McsvApiClient client,
            WorldPackState state
    ) {
        if (state == null || !state.changed) return;
        try {
            if (state.existed) {
                JSONObject args = new JSONObject();
                args.put("path", state.path());
                args.put("content", state.originalContent);
                client.callTool("files_write", args);
            } else {
                bestEffortDelete(client, state.worldPath, state.fileName);
            }
        } catch (Exception ignored) {
            // Best-effort transaction rollback. MCSV also versions text writes.
        }
    }

    static JSONArray mergeWorldPackConfig(
            JSONArray current,
            PackInfo pack
    ) throws IOException {
        JSONArray merged = new JSONArray();
        try {
            if (current != null) {
                for (int i = 0; i < current.length(); i++) {
                    JSONObject item = current.optJSONObject(i);
                    if (item == null) continue;
                    if (pack.uuid.equalsIgnoreCase(item.optString("pack_id", ""))) {
                        continue;
                    }
                    merged.put(new JSONObject(item.toString()));
                }
            }

            JSONObject installed = new JSONObject();
            installed.put("pack_id", pack.uuid);
            JSONArray version = new JSONArray();
            version.put(pack.version[0]);
            version.put(pack.version[1]);
            version.put(pack.version[2]);
            installed.put("version", version);
            merged.put(installed);
            return merged;
        } catch (JSONException error) {
            throw new IOException("รวมรายการ Bedrock Addon ไม่สำเร็จ", error);
        }
    }

    private static String readText(
            McsvApiClient client,
            String path
    ) throws IOException {
        JSONObject result = client.callTool(
                "files_read",
                object("path", path)
        );
        if (result.optBoolean("truncated", false)) {
            throw new IOException("ไฟล์ " + path + " ใหญ่เกินกว่าจะอ่านอย่างปลอดภัย");
        }
        return result.optString("content", "");
    }

    private static boolean pathExists(
            McsvApiClient client,
            String directory,
            String name
    ) throws IOException {
        JSONObject listing = client.callTool(
                "files_list",
                object("directory", directory)
        );
        JSONArray files = listing.optJSONArray("files");
        if (files == null) return false;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file != null && name.equals(file.optString("name", ""))) {
                return true;
            }
        }
        return false;
    }

    private static void rename(
            McsvApiClient client,
            String from,
            String to
    ) throws IOException {
        JSONArray renames = new JSONArray();
        JSONObject item = new JSONObject();
        JSONObject args = new JSONObject();
        try {
            item.put("from", from);
            item.put("to", to);
            renames.put(item);
            args.put("root", "/");
            args.put("renames", renames);
        } catch (JSONException impossible) {
            throw new IOException("Could not prepare MCSV file move", impossible);
        }
        client.callTool("files_rename", args);
    }

    private static void bestEffortRename(
            McsvApiClient client,
            String from,
            String to
    ) {
        try {
            rename(client, from, to);
        } catch (Exception ignored) {
            // Best-effort rollback only.
        }
    }

    private static void bestEffortDelete(
            McsvApiClient client,
            String root,
            String file
    ) {
        try {
            JSONArray files = new JSONArray();
            files.put(file);
            JSONObject args = new JSONObject();
            args.put("root", root);
            args.put("files", files);
            client.callTool("files_delete", args);
        } catch (Exception ignored) {
            // Temporary staging/backup cleanup is best effort.
        }
    }

    private static JSONObject object(
            String key1,
            Object value1
    ) throws IOException {
        JSONObject result = new JSONObject();
        try {
            result.put(key1, value1);
            return result;
        } catch (JSONException impossible) {
            throw new IOException("Could not prepare MCSV request", impossible);
        }
    }

    private static JSONObject object(
            String key1,
            Object value1,
            String key2,
            Object value2
    ) throws IOException {
        JSONObject result = object(key1, value1);
        try {
            result.put(key2, value2);
            return result;
        } catch (JSONException impossible) {
            throw new IOException("Could not prepare MCSV request", impossible);
        }
    }

    private static String stripLeadingSlash(String path) {
        return path != null && path.startsWith("/") ? path.substring(1) : path;
    }

    private static boolean isSafeName(String name) {
        return name != null
                && !name.isEmpty()
                && !name.equals(".")
                && !name.equals("..")
                && !name.contains("/")
                && !name.contains("\\");
    }

    private static final class WorldPackState {
        final String worldPath;
        final String fileName;
        final boolean existed;
        final String originalContent;
        final JSONArray entries;
        boolean changed;

        WorldPackState(
                String worldPath,
                String fileName,
                boolean existed,
                String originalContent,
                JSONArray entries
        ) {
            this.worldPath = worldPath;
            this.fileName = fileName;
            this.existed = existed;
            this.originalContent = originalContent;
            this.entries = entries;
        }

        String path() {
            return worldPath + "/" + fileName;
        }
    }

    private static final class PackSwap {
        final String destinationRelative;
        final String backupRelative;
        final boolean hadExisting;

        PackSwap(
                String destinationRelative,
                String backupRelative,
                boolean hadExisting
        ) {
            this.destinationRelative = destinationRelative;
            this.backupRelative = backupRelative;
            this.hadExisting = hadExisting;
        }
    }

    enum PackKind {
        BEHAVIOR,
        RESOURCE
    }

    static final class PackInfo {
        final String directory;
        final String uuid;
        final int[] version;
        final PackKind kind;

        PackInfo(
                String directory,
                String uuid,
                int[] version,
                PackKind kind
        ) {
            this.directory = directory;
            this.uuid = uuid;
            this.version = version;
            this.kind = kind;
        }
    }

    static final class InstallResult {
        final String addonName;
        final String sha256;
        final String levelName;
        final String behaviorUuid;
        final String resourceUuid;

        InstallResult(
                String addonName,
                String sha256,
                String levelName,
                String behaviorUuid,
                String resourceUuid
        ) {
            this.addonName = addonName;
            this.sha256 = sha256;
            this.levelName = levelName;
            this.behaviorUuid = behaviorUuid;
            this.resourceUuid = resourceUuid;
        }
    }
}
