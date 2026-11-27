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
 * Keeps the AI keys (Claude API key, Hermes API server key) on this phone only. It is encrypted with a key held in the
 * Android Keystore (which never leaves the device), lives in its own prefs file that is
 * excluded from cloud backup and device transfer, and is never handed back to the page.
 */
final class AiKeyStore {

    private static final String PREFS = "ledger_ai";   // excluded in backup_rules / data_extraction_rules
    private static final String ALIAS = "ledger_ai_key";

    private final Context ctx;

    AiKeyStore(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean looksLikeKey(String k) {
        return k != null && k.trim().matches("sk-ant-[A-Za-z0-9_\\-]{20,}");
    }

    // Claude key ("k") and Hermes key ("h") are encrypted; the chosen AI and the Hermes
    // address are plain settings in the same backup-excluded file.
    static final String CLAUDE = "k", HERMES = "h";

    boolean has() { return get() != null; }
    String tail() { return tail(CLAUDE); }
    boolean set(String key) { return looksLikeKey(key) && setSecret(CLAUDE, key); }
    void clear() { prefs().edit().remove(CLAUDE).commit(); }
    String get() { return getSecret(CLAUDE); }

    boolean has(String field) { return getSecret(field) != null; }

    /** Last 4 characters, for "Key ending in …abcd". */
    String tail(String field) {
        String k = getSecret(field);
        return k == null || k.length() < 4 ? "" : k.substring(k.length() - 4);
    }

    String provider() {
        String p = prefs().getString("provider", "claude");
        return "hermes".equals(p) ? "hermes" : "claude";
    }
    void setProvider(String p) { prefs().edit().putString("provider", "hermes".equals(p) ? "hermes" : "claude").commit(); }

    String hermesUrl() { return prefs().getString("hermesUrl", ""); }
    void setHermes(String url, String key) {
        SharedPreferences.Editor e = prefs().edit().putString("hermesUrl", url);
        e.commit();
        if (key != null && !key.trim().isEmpty()) setSecret(HERMES, key);
    }
    void clearHermes() { prefs().edit().remove("hermesUrl").remove(HERMES).commit(); }

    boolean setSecret(String field, String value) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, secret());
            byte[] ct = c.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
            String v = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
            return prefs().edit().putString(field, v).commit();
        } catch (Exception e) {
            return false;
        }
    }

    /** The secret, or null if none is saved or it can't be read on this phone. */
    String getSecret(String field) {
        String v = prefs().getString(field, null);
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
