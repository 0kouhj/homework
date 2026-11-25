package com.homework.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores per-platform credentials encrypted with an AndroidKeyStore AES/GCM key.
 * Only the two supported platforms are accepted. Nothing is ever logged.
 */
public final class AccountVault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALIAS_PREFIX = "homework.account_vault.";
    private static final String PREFS_PREFIX = "account_vault_";
    private static final String FIELD = "credential";
    private static final String VERSION = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String ERROR = "账号信息处理失败";
    private static final String UNSUPPORTED = "不支持的平台";
    private static final Object LOCK = new Object();

    private final Context context;

    public AccountVault(Context context) {
        if (context == null) throw new IllegalArgumentException("上下文无效");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
    }

    public record Account(String username, String password) {
        @Override
        public String toString() {
            return "Account[username=***, password=***]";
        }
    }

    public Account read(String platform) {
        checkPlatform(platform);
        synchronized (LOCK) {
            final String value;
            try {
                value = prefs(platform).getString(FIELD, null);
            } catch (RuntimeException e) {
                throw failure();
            }
            if (value == null) return null;
            try {
                if (!value.startsWith(VERSION)) throw new GeneralSecurityException();
                String[] parts = value.substring(VERSION.length()).split(":", -1);
                if (parts.length != 2) throw new GeneralSecurityException();
                byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
                byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
                if (iv.length != IV_BYTES || encrypted.length < TAG_BITS / 8) {
                    throw new GeneralSecurityException();
                }
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key(platform), new GCMParameterSpec(TAG_BITS, iv));
                byte[] plain = cipher.doFinal(encrypted);
                try {
                    JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                    Object username = json.get("username");
                    Object password = json.get("password");
                    if (!(username instanceof String) || !(password instanceof String)) {
                        throw new JSONException("malformed");
                    }
                    return new Account((String) username, (String) password);
                } finally {
                    Arrays.fill(plain, (byte) 0);
                }
            } catch (Exception e) {
                throw failure();
            }
        }
    }

    public void save(String platform, Account account) {
        checkPlatform(platform);
        if (account == null || account.username() == null || account.password() == null) {
            throw new IllegalArgumentException("账号信息不完整");
        }
        synchronized (LOCK) {
            byte[] plain = null;
            try {
                JSONObject json = new JSONObject();
                json.put("username", account.username());
                json.put("password", account.password());
                plain = json.toString().getBytes(StandardCharsets.UTF_8);

                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, key(platform));
                byte[] iv = cipher.getIV();
                if (iv == null || iv.length != IV_BYTES) throw new GeneralSecurityException();
                byte[] encrypted = cipher.doFinal(plain);

                String value = VERSION + Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                        + Base64.encodeToString(encrypted, Base64.NO_WRAP);
                if (!prefs(platform).edit().putString(FIELD, value).commit()) {
                    throw new IllegalStateException(ERROR);
                }
            } catch (Exception e) {
                throw failure();
            } finally {
                if (plain != null) Arrays.fill(plain, (byte) 0);
            }
        }
    }

    public void clear(String platform) {
        checkPlatform(platform);
        synchronized (LOCK) {
            try {
                if (!prefs(platform).edit().remove(FIELD).commit()) throw failure();
            } catch (IllegalStateException e) {
                throw e;
            } catch (RuntimeException e) {
                throw failure();
            }
        }
    }

    public boolean has(String platform) {
        checkPlatform(platform);
        synchronized (LOCK) {
            try {
                return prefs(platform).contains(FIELD);
            } catch (RuntimeException e) {
                throw failure();
            }
        }
    }

    private SharedPreferences prefs(String platform) {
        return context.getSharedPreferences(PREFS_PREFIX + platform, Context.MODE_PRIVATE);
    }

    private static String alias(String platform) {
        return ALIAS_PREFIX + platform;
    }

    private static void checkPlatform(String platform) {
        if (!Store.CHAOXING.equals(platform) && !Store.MOOC.equals(platform)) {
            throw new IllegalArgumentException(UNSUPPORTED);
        }
    }

    private static IllegalStateException failure() {
        return new IllegalStateException(ERROR);
    }

    private static SecretKey key(String platform) throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            String alias = alias(platform);
            if (keyStore.containsAlias(alias)) {
                Key key = keyStore.getKey(alias, null);
                if (key instanceof SecretKey) return (SecretKey) key;
                throw new GeneralSecurityException();
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException();
        }
    }
}
