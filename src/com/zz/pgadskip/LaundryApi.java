package com.zz.pgadskip;

import android.content.Context;
import android.os.Build;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** 仅包含登录和最近设备查询；不包含付款、预约或设备控制接口。 */
final class LaundryApi {
    private final String version;
    private final String appSecretB64;
    LaundryApi(Context c) {
        appSecretB64 = c.getString(R.string.qiekj_app_secret_b64).trim();
        String v = "1.143.1";
        try { v = c.getPackageManager().getPackageInfo("com.qiekj.user", 0).versionName; } catch (Exception ignored) { }
        version = v;
    }

    static final class ApiException extends Exception {
        final boolean auth;
        final boolean throttled;
        ApiException(String message, boolean auth, boolean throttled) { super(message); this.auth = auth; this.throttled = throttled; }
    }

    static Map<String, String> form(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private Object post(String path, String token, Map<String, String> fields) throws Exception {
        // 固定允许的业务路径，拒绝跨主机重定向，避免会话被带往其他地址。
        if (!path.equals("/goods/latestUsed") && !path.equals("/common/isNeedCaptcha")
                && !path.equals("/common/sms/sendCode") && !path.equals("/user/reg")) throw new IllegalArgumentException("unsupported query");
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://userapi.qiekj.com" + path).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", token);
            connection.setRequestProperty("Version", version);
            connection.setRequestProperty("channel", "android_app");
            connection.setRequestProperty("phoneBrand", Build.BRAND);
            connection.setRequestProperty("User-Agent", "PGAdSkip/5.1 (Android " + Build.VERSION.RELEASE + ")");
            String timestamp = Long.toString(System.currentTimeMillis());
            // 与本机胖乖 1.143.1 的普通请求签名一致；不是用户密钥。
            // 参数不进仓库：构建时从仓库外的文件写进资源（见 build.ps1 的 -AppSecretFile）。
            if (appSecretB64.isEmpty()) throw new ApiException("这个安装包构建时没有带接口参数，无法查询洗衣机状态", false, false);
            String appSecret = new String(Base64.decode(appSecretB64, Base64.DEFAULT), StandardCharsets.UTF_8);
            String signed = "appSecret=" + appSecret + "&channel=android_app&timestamp=" + timestamp
                    + "&token=" + token + "&version=" + version + "&" + path;
            StringBuilder sign = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(signed.getBytes(StandardCharsets.UTF_8)))
                sign.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            connection.setRequestProperty("timestamp", timestamp);
            connection.setRequestProperty("sign", sign.toString());
            Map<String, String> data = new LinkedHashMap<>(fields);
            if (!token.isEmpty()) data.put("token", token);
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : data.entrySet()) {
                if (body.length() > 0) body.append('&');
                body.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new ApiException("登录失效或访问被拒绝，请重新登录后重试", true, false);
            if (status == 429) throw new ApiException("查询过于频繁，已降低查询频率", false, true);
            if (status != 200) throw new ApiException("服务暂不可用（HTTP " + status + "）", false, false);
            String response;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (out.size() + n > 1024 * 1024) throw new Exception("服务返回过大，请稍后重试");
                    out.write(buffer, 0, n);
                }
                response = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject root = new JSONObject(response);
            if (!root.has("code") || !"0".equals(root.optString("code"))) {
                String msg = root.optString("msg", "查询未成功");
                boolean auth = "2".equals(root.optString("code"))
                        || msg.matches("(?i).*(未登录|登录.*(失效|过期|超时)|token.*(失效|过期|invalid)).*");
                // 不显示可能包含凭证的完整服务端响应。
                if (!token.isEmpty()) msg = msg.replace(token, "[已隐藏]");
                if (msg.length() > 100) msg = msg.substring(0, 100);
                throw new ApiException(msg, auth, false);
            }
            return root.opt("data");
        } finally { connection.disconnect(); }
    }

    void sendCode(String phone) throws Exception {
        Object need = post("/common/isNeedCaptcha", "", form("phone", phone));
        if (Boolean.TRUE.equals(need)) throw new Exception("胖乖要求图形验证。请先在官方应用完成验证，或使用下方的已有 Token 登录。");
        if (!Boolean.FALSE.equals(need)) throw new Exception("无法确认验证要求，请稍后重试");
        post("/common/sms/sendCode", "", form("phone", phone, "template", "reg"));
    }

    String login(String phone, String code) throws Exception {
        Object data = post("/user/reg", "", form("phone", phone, "verify", code, "channel", "android_app"));
        String token = data instanceof JSONObject ? ((JSONObject) data).optString("token") : "";
        if (token.isEmpty()) throw new Exception("登录需要进一步验证，请在胖乖官方应用检查账号");
        return token;
    }

    List<LaundryDevice> recent(String token) throws Exception {
        Object data = post("/goods/latestUsed", token, form("categoryCode", "00"));
        if (!(data instanceof JSONArray)) throw new Exception("设备列表格式已变化，暂时无法查询");
        JSONArray a = (JSONArray) data;
        LinkedHashMap<String, LaundryDevice> found = new LinkedHashMap<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject d = a.optJSONObject(i);
            if (d == null || !"00".equals(d.optString("categoryCode"))) continue;
            String id = d.optString("goodsId");
            if (id.isEmpty() || "null".equals(id)) continue;
            found.put(id, new LaundryDevice(id, d.optString("goodsName", "洗衣机"), d.optString("orgName"), d.optInt("workStatus", 0)));
        }
        return new ArrayList<>(found.values());
    }
}
