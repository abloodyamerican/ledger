package com.max.ledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps the Claude API key on this phone only. It is encrypted with a key held in the
 * Android Keystore (which never leaves the device), lives in its own prefs file that is
 * excluded from cloud backup and device transfer, and is never handed back to the page.
 */
final class AiKeyStore {

    private static final String PREFS = "ledger_ai";   // excluded in backup_rules / data_extraction_rules
    private static final String ALIAS = "ledger_ai_key";
    private static final String FIELD = "k";

    private final Context ctx;

    AiKeyStore(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean looksLikeKey(String k) {
        return k != null && k.trim().matches("sk-ant-[A-Za-z0-9_\\-]{20,}");
    }

    boolean has() { return get() != null; }

    /** Last 4 characters, for "Key ending in …abcd". */
    String tail() {
        String k = get();
        return k == null ? "" : k.substring(k.length() - 4);
    }

    boolean set(String key) {
        if (!looksLikeKey(key)) return false;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, secret());
            byte[] ct = c.doFinal(key.trim().getBytes(StandardCharsets.UTF_8));
            String v = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
            return prefs().edit().putString(FIELD, v).commit();
        } catch (Exception e) {
            return false;
        }
    }

    void clear() { prefs().edit().remove(FIELD).commit(); }

    /** The key, or null if none is saved or it can't be read on this phone. */
    String get() {
        String v = prefs().getString(FIELD, null);
        if (v == null) return null;
        try {
            String[] p = v.split(":", 2);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey secret() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
    }
}
