package cn.boya.monitor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import android.util.Base64;
import android.webkit.CookieManager;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

public final class ConfigStore {
    private static final String PREF = "boya_local";
    private static final String KEY = "AndroidKeyStore";
    private static final String ALIAS = "boya-monitor-key";
    private ConfigStore() {}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    public static JSONObject config(Context c) {
        SharedPreferences p = prefs(c); JSONObject x = new JSONObject();
        try {
            String[] fields = {"username","delivery_mode","campus","smtp_host","smtp_port","smtp_username","smtp_from","smtp_to","qq_to","poll_interval","notify_channel","free_ranges"};
            for (String f : fields) if (p.contains(f)) {
                String v = p.getString(f, "");
                if ("free_ranges".equals(f)) x.put(f, v.isEmpty() ? new JSONObject() : new JSONObject(v));
                else x.put(f, v);
            }
            x.put("password", decrypt(p.getString("password_enc", "")));
            x.put("smtp_password", decrypt(p.getString("smtp_password_enc", "")));
        } catch (Exception ignored) {}
        return x;
    }
    public static JSONObject publicConfig(Context c) { return config(c); }

    public static void save(Context c, JSONObject x) throws Exception {
        SharedPreferences.Editor e = prefs(c).edit();
        String[] fields = {"username","delivery_mode","campus","smtp_host","smtp_port","smtp_username","smtp_from","smtp_to","qq_to","poll_interval","notify_channel"};
        for (String f : fields) if (x.has(f)) e.putString(f, x.optString(f, ""));
        if (x.has("free_ranges")) e.putString("free_ranges", x.optJSONObject("free_ranges").toString());
        if (x.has("password") && !x.optString("password", "").isEmpty()) e.putString("password_enc", encrypt(x.optString("password")));
        if (x.has("smtp_password") && !x.optString("smtp_password", "").isEmpty()) e.putString("smtp_password_enc", encrypt(x.optString("smtp_password")));
        e.apply();
    }

    public static JSONObject status(Context c) {
        try { return new JSONObject(prefs(c).getString("status", "{\"running\":false,\"courses\":[]}")); }
        catch (Exception e) { return new JSONObject(); }
    }
    public static void status(Context c, JSONObject value) { prefs(c).edit().putString("status", value.toString()).apply(); }
    public static void start(Context c) {
        Intent i = new Intent(c, MonitorService.class).setAction(MonitorService.ACTION_RUN);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void saveWebCookies(Context c) {
        try {
            CookieManager cm = CookieManager.getInstance();
            JSONObject cookies = new JSONObject();
            cookies.put("bykc", cm.getCookie("https://bykc.buaa.edu.cn") == null ? "" : cm.getCookie("https://bykc.buaa.edu.cn"));
            cookies.put("sso", cm.getCookie("https://sso.buaa.edu.cn") == null ? "" : cm.getCookie("https://sso.buaa.edu.cn"));
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("web_cookies_enc", encrypt(cookies.toString())).apply();
        } catch (Exception ignored) {}
    }

    public static void restoreWebCookies(Context c) {
        try {
            String raw = decrypt(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("web_cookies_enc", ""));
            if (raw.isEmpty()) return;
            JSONObject cookies = new JSONObject(raw);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setCookie("https://bykc.buaa.edu.cn", cookies.optString("bykc", ""));
            cm.setCookie("https://sso.buaa.edu.cn", cookies.optString("sso", ""));
            cm.flush();
        } catch (Exception ignored) {}
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEY); ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return kg.generateKey();
    }
    private static String encrypt(String value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        byte[] out = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[c.getIV().length + out.length]; System.arraycopy(c.getIV(), 0, all, 0, c.getIV().length); System.arraycopy(out, 0, all, c.getIV().length, out.length);
        return Base64.encodeToString(all, Base64.NO_WRAP);
    }
    private static String decrypt(String value) {
        if (value == null || value.isEmpty()) return "";
        try { byte[] all = Base64.decode(value, Base64.DEFAULT); byte[] iv = new byte[12]; byte[] data = new byte[all.length - 12]; System.arraycopy(all,0,iv,0,12); System.arraycopy(all,12,data,0,data.length); Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv)); return new String(c.doFinal(data), StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
    }
}
