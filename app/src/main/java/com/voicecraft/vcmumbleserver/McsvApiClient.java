package com.voicecraft.vcmumbleserver;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class McsvApiClient {
    private static final String API_BASE = "https://api.mcsv.me/api/v1";
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final String apiKey;

    McsvApiClient(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("กรุณาใส่ MCSV API Key");
        }
        if (!value.startsWith("mcsv_")) {
            throw new IllegalArgumentException("MCSV API Key ต้องขึ้นต้นด้วย mcsv_");
        }
        this.apiKey = value;
    }

    JSONObject validateKey() throws IOException {
        return request("GET", "/me", null);
    }

    JSONObject callTool(String name, JSONObject arguments) throws IOException {
        if (name == null || !name.matches("[a-z0-9_]+")) {
            throw new IOException("Invalid MCSV tool name");
        }
        return request(
                "POST",
                "/tools/" + name,
                arguments == null ? new JSONObject() : arguments
        );
    }

    private JSONObject request(String method, String path, JSONObject body)
            throws IOException {
        URL url = new URL(API_BASE + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "VC-Mumble-Server-Android");

        if (body != null) {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
        }

        int status = connection.getResponseCode();
        byte[] responseBytes;
        try {
            if (status >= 200 && status < 300) {
                responseBytes = EndstoneReleaseResolver.readLimited(
                        connection.getInputStream(),
                        MAX_RESPONSE_BYTES
                );
            } else if (connection.getErrorStream() != null) {
                responseBytes = EndstoneReleaseResolver.readLimited(
                        connection.getErrorStream(),
                        MAX_RESPONSE_BYTES
                );
            } else {
                responseBytes = new byte[0];
            }
        } finally {
            connection.disconnect();
        }

        String responseText = new String(responseBytes, StandardCharsets.UTF_8);
        JSONObject response = null;
        if (!responseText.trim().isEmpty()) {
            try {
                response = new JSONObject(responseText);
            } catch (JSONException error) {
                throw new IOException("MCSV API returned invalid JSON (HTTP " + status + ")", error);
            }
        }

        if (status < 200 || status >= 300) {
            String message = response == null
                    ? "MCSV API HTTP " + status
                    : response.optString("error", "MCSV API HTTP " + status);
            throw new ApiException(status, message);
        }

        if (response == null) {
            throw new IOException("MCSV API returned an empty response");
        }
        if (!response.optBoolean("ok", false)) {
            throw new ApiException(
                    status,
                    response.optString("error", "MCSV API request failed")
            );
        }

        Object result = response.opt("result");
        if (result instanceof JSONObject) {
            return (JSONObject) result;
        }

        JSONObject wrapped = new JSONObject();
        try {
            wrapped.put("value", result);
        } catch (JSONException impossible) {
            throw new IOException("Could not read MCSV API result", impossible);
        }
        return wrapped;
    }

    static final class ApiException extends IOException {
        final int statusCode;

        ApiException(int statusCode, String message) {
            super(message == null || message.isEmpty()
                    ? "MCSV API HTTP " + statusCode
                    : message);
            this.statusCode = statusCode;
        }
    }
}
