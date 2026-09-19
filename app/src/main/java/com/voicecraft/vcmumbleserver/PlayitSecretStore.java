package com.voicecraft.vcmumbleserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class PlayitSecretStore {
    private static final String PREFS = "vc_playit";
    private static final String PREF_KEY = "agent_secret_enc";
    private static final String KEY_ALIAS = "vc_mumble_playit_secret_v1";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";

    private PlayitSecretStore() {}

    static boolean hasStored(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_KEY, "");
        return value != null && !value.isEmpty();
    }

    static String load(Context context) {
        String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_KEY, "");
        if (encoded == null || encoded.isEmpty()) return "";

        try {
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            int ivLength = buffer.getInt();
            if (ivLength < 12 || ivLength > 32 || buffer.remaining() <= ivLength) return "";

            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception error) {
            ServerLog.append(context, "PLAYIT", "Unable to decrypt stored Playit secret: "
                    + error.getClass().getSimpleName());
            return "";
        }
    }

    static void save(Context context, String secret) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (secret == null || secret.isEmpty()) {
            prefs.edit().remove(PREF_KEY).commit();
            return;
        }

        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));

            ByteBuffer packed = ByteBuffer.allocate(4 + iv.length + ciphertext.length);
            packed.putInt(iv.length);
            packed.put(iv);
            packed.put(ciphertext);

            if (!prefs.edit()
                    .putString(PREF_KEY, Base64.encodeToString(packed.array(), Base64.NO_WRAP))
                    .commit()) {
                throw new IllegalStateException("SharedPreferences commit failed");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not protect Playit secret", error);
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
