package com.zz.pgadskip;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 会话只存在应用私有目录，Token 由 Android Keystore 密钥加密。 */
final class LaundryStore {
    private static final String KEY = "pgadskip_laundry_session_v1";
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("laundry", Context.MODE_PRIVATE); }
    static boolean loggedIn(Context c) { return !sp(c).getString("session", "").isEmpty(); }
    static boolean enabled(Context c) { return sp(c).getBoolean("watch", false); }
    static String epoch(Context c) { return sp(c).getString("epoch", ""); }
    static int interval(Context c) {
        int n = sp(c).getInt("interval", 60);
        return n == 30 || n == 60 || n == 120 || n == 300 ? n : 60;
    }
    static Set<String> selected(Context c) { return new HashSet<>(sp(c).getStringSet("selected", new HashSet<>())); }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY)) return (SecretKey) ks.getKey(KEY, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return g.generateKey();
    }

    static synchronized String token(Context c) throws Exception {
        String saved = sp(c).getString("session", "");
        if (saved.isEmpty()) return "";
        String[] parts = saved.split(":", -1);
        if (parts.length != 2) throw new Exception("登录信息不可读，请重新登录");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }

    static synchronized void login(Context c, String token, String label) throws Exception {
        if (token == null || token.isEmpty()) throw new Exception("登录未返回有效会话");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String saved = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        int interval = interval(c);
        if (!sp(c).edit().clear().putInt("interval", interval).putString("session", saved)
                .putString("account", label).putString("epoch", UUID.randomUUID().toString()).commit()) {
            throw new Exception("无法保存登录信息，请检查存储空间");
        }
    }

    static synchronized void logout(Context c) {
        int interval = interval(c);
        sp(c).edit().clear().putInt("interval", interval).putString("epoch", UUID.randomUUID().toString()).commit();
    }

    static synchronized List<LaundryDevice> devices(Context c) {
        List<LaundryDevice> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("devices", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject d = a.getJSONObject(i);
                out.add(new LaundryDevice(d.getString("id"), d.optString("name"), d.optString("org"), d.optInt("state")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static synchronized boolean publish(Context c, String epoch, List<LaundryDevice> found) throws Exception {
        if (!epoch.equals(epoch(c))) return false;
        LinkedHashMap<String, LaundryDevice> merged = new LinkedHashMap<>();
        for (LaundryDevice d : found) merged.put(d.id, d);
        Set<String> selected = selected(c);
        for (LaundryDevice d : devices(c)) if (selected.contains(d.id) && !merged.containsKey(d.id))
            merged.put(d.id, new LaundryDevice(d.id, d.name, d.org, LaundryDevice.UNKNOWN));
        JSONArray a = new JSONArray();
        for (LaundryDevice d : merged.values()) a.put(new JSONObject().put("id", d.id).put("name", d.name).put("org", d.org).put("state", d.state));
        return sp(c).edit().putString("devices", a.toString()).putLong("checked", System.currentTimeMillis()).putString("error", "").commit();
    }

    static synchronized void error(Context c, String epoch, String message) {
        if (epoch.equals(epoch(c))) sp(c).edit().putString("error", message).apply();
    }
}
