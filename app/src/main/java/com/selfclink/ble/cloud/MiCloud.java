package com.selfclink.ble.cloud;

import android.util.Base64;

import com.selfclink.ble.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 米家云客户端（账号密码登录 + 扫码登录 + 拉设备/BindKey），算法对齐开源
 * 「Xiaomi-cloud-tokens-extractor」。纯客户端，账号凭据仅存内存（{@link MiAccount}），用完即弃。
 *
 * <p>登录票据/密码不落盘、不上传第三方；仅与小米官方 account.xiaomi.com / *.api.io.mi.com 通信。
 * 网络调用须在子线程执行。
 */
public final class MiCloud {

    private static final String TAG = "MiCloud";
    private static final String UA =
            "Android-7.1.1-1.0.0-ONEPLUS A3010-136-AGENT-ID APP/xiaomi.smarthome APPV/62830";

    private final String deviceId = randomUpper(16);
    private final CookieManager cookieManager;

    public MiCloud() {
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookieManager);
    }

    // ============================ 账号密码登录 ============================

    /** 登录结果。{@link #ok} 为 false 时 {@link #needVerifyUrl} 可能非空（需二次验证）。 */
    public static final class LoginResult {
        public final boolean ok;
        public final String message;
        public final String needVerifyUrl;

        LoginResult(boolean ok, String message, String needVerifyUrl) {
            this.ok = ok;
            this.message = message;
            this.needVerifyUrl = needVerifyUrl;
        }
    }

    public LoginResult passwordLogin(String username, String password, String region) {
        try {
            String sign = loginStep1();
            JSONObject auth = loginStep2(username, password, sign);
            if (auth == null || auth.optString("ssecurity").length() <= 4) {
                String notif = auth == null ? null : auth.optString("notificationUrl", null);
                return new LoginResult(false,
                        notif != null ? "需要二次验证" : "账号或密码错误", notif);
            }
            String ssecurity = auth.getString("ssecurity");
            String userId = String.valueOf(auth.get("userId"));
            String location = auth.getString("location");
            String serviceToken = loginStep3(location);
            if (serviceToken == null) {
                return new LoginResult(false, "获取 serviceToken 失败", null);
            }
            MiAccount.get(null).set(userId, ssecurity, serviceToken, region);
            AppLog.d(TAG, "密码登录成功 userId=" + userId);
            return new LoginResult(true, "登录成功", null);
        } catch (Exception e) {
            AppLog.e(TAG, "密码登录失败", e);
            return new LoginResult(false, "登录异常: " + e.getMessage(), null);
        }
    }

    private String loginStep1() throws IOException {
        HttpURLConnection c = open("https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_json=true", "GET");
        c.setRequestProperty("Cookie", "sdkVersion=accountsdk-18.8.15; deviceId=" + deviceId);
        String body = readBody(c);
        JSONObject json = parseMiJson(body);
        return json.optString("_sign");
    }

    private JSONObject loginStep2(String username, String password, String sign) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("sid", "xiaomiio");
        fields.put("hash", md5Upper(password));
        fields.put("callback", "https://sts.api.io.mi.com/sts");
        fields.put("qs", "%3Fsid%3Dxiaomiio%26_json%3Dtrue");
        fields.put("user", username);
        fields.put("_sign", sign);
        fields.put("_json", "true");

        HttpURLConnection c = open("https://account.xiaomi.com/pass/serviceLoginAuth2", "POST");
        c.setRequestProperty("Cookie", "sdkVersion=accountsdk-18.8.15; deviceId=" + deviceId);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        writeForm(c, fields);
        return parseMiJson(readBody(c));
    }

    private String loginStep3(String location) throws IOException {
        HttpURLConnection c = open(location, "GET");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        readBody(c);
        return cookie("serviceToken");
    }

    // ============================ 扫码登录 ============================

    /** 扫码会话：{@link #qrContent} 渲染成二维码给用户扫，{@link #longPollUrl} 用于轮询结果。 */
    public static final class QrSession {
        public final String qrContent;
        public final String longPollUrl;

        QrSession(String qrContent, String longPollUrl) {
            this.qrContent = qrContent;
            this.longPollUrl = longPollUrl;
        }
    }

    /** 拉起扫码会话（取二维码内容 + 长轮询地址）。 */
    public QrSession startQrLogin() {
        try {
            String url = "https://account.xiaomi.com/longPolling/loginUrl?_qrsize=240"
                    + "&qs=%3Fsid%3Dxiaomiio%26_json%3Dtrue"
                    + "&callback=https%3A%2F%2Fsts.api.io.mi.com%2Fsts"
                    + "&_hasLogo=false&theme=&sid=xiaomiio&needTheme=false&showActiveX=false"
                    + "&serviceParam=%7B%22checkSafePhone%22%3Afalse%7D&_locale=zh_CN&_json=true";
            HttpURLConnection c = open(url, "GET");
            c.setRequestProperty("Cookie", "sdkVersion=accountsdk-18.8.15; deviceId=" + deviceId);
            JSONObject json = parseMiJson(readBody(c));
            String loginUrl = json.optString("loginUrl");
            String lp = json.optString("lp");
            if (loginUrl.isEmpty() || lp.isEmpty()) {
                return null;
            }
            return new QrSession(loginUrl, lp);
        } catch (Exception e) {
            AppLog.e(TAG, "扫码会话创建失败", e);
            return null;
        }
    }

    /**
     * 长轮询等待扫码确认（阻塞，建议在子线程循环调用直到非 WAITING）。
     */
    public LoginResult pollQrLogin(QrSession session, String region) {
        try {
            HttpURLConnection c = open(session.longPollUrl, "GET");
            c.setRequestProperty("Cookie", "sdkVersion=accountsdk-18.8.15; deviceId=" + deviceId);
            JSONObject json = parseMiJson(readBody(c));
            int code = json.optInt("code", -1);
            if (code != 0) {
                // 0=成功；其它=等待/过期，调用方据 message 决定是否重试
                return new LoginResult(false, "WAITING", null);
            }
            String ssecurity = json.optString("ssecurity");
            String userId = String.valueOf(json.opt("userId"));
            String location = json.optString("location");
            if (ssecurity.isEmpty() || location.isEmpty()) {
                return new LoginResult(false, "扫码返回缺字段", null);
            }
            String serviceToken = loginStep3(location);
            if (serviceToken == null) {
                return new LoginResult(false, "获取 serviceToken 失败", null);
            }
            MiAccount.get(null).set(userId, ssecurity, serviceToken, region);
            AppLog.d(TAG, "扫码登录成功 userId=" + userId);
            return new LoginResult(true, "登录成功", null);
        } catch (Exception e) {
            AppLog.e(TAG, "扫码轮询失败", e);
            return new LoginResult(false, "WAITING", null);
        }
    }

    // ============================ 拉设备 + BindKey ============================

    public List<MiDevice> getDevices() {
        List<MiDevice> out = new ArrayList<>();
        MiAccount acc = MiAccount.get(null);
        if (!acc.isLoggedIn()) {
            return out;
        }
        try {
            String resp = apiPost("/home/device_list",
                    "{\"getVirtualModel\":false,\"getHuamiDevices\":0}", acc);
            JSONObject json = new JSONObject(resp);
            JSONArray list = json.getJSONObject("result").optJSONArray("list");
            if (list == null) {
                return out;
            }
            for (int i = 0; i < list.length(); i++) {
                JSONObject d = list.getJSONObject(i);
                String name = d.optString("name", "");
                String mac = normMac(d.optString("mac", ""));
                String did = d.optString("did", "");
                String model = d.optString("model", "");
                String beaconKey = fetchBeaconKey(did, acc);
                out.add(new MiDevice(name, mac, did, model, beaconKey));
            }
        } catch (Exception e) {
            AppLog.e(TAG, "拉设备失败", e);
        }
        return out;
    }

    private String fetchBeaconKey(String did, MiAccount acc) {
        if (did == null || did.isEmpty()) {
            return null;
        }
        try {
            String resp = apiPost("/v2/device/blt_get_beaconkey",
                    "{\"did\":\"" + did + "\",\"pdid\":1}", acc);
            JSONObject json = new JSONObject(resp);
            JSONObject result = json.optJSONObject("result");
            if (result == null) {
                return null;
            }
            String bk = result.optString("beaconkey", "");
            return bk.isEmpty() ? null : bk.toUpperCase();
        } catch (Exception e) {
            return null; // 非 BLE/Mesh 设备没有 beaconkey，忽略
        }
    }

    // ============================ 签名请求 ============================

    private String apiBase(String region) {
        if (region == null || region.isEmpty() || region.equalsIgnoreCase("cn")) {
            return "https://api.io.mi.com/app";
        }
        return "https://" + region.toLowerCase() + ".api.io.mi.com/app";
    }

    private String apiPost(String path, String data, MiAccount acc) throws Exception {
        String base = apiBase(acc.region);
        String fullUrl = base + path;
        // 签名用的路径不含 /app 前缀（对齐开源算法 url.split("com")[1].replace("/app/","/")）。
        String signPath = path;

        String nonce = genNonce();
        String signedNonce = signedNonce(acc.ssecurity, nonce);

        // 1) 明文参数先算 rc4_hash__，再把全部值 RC4 加密，最后对密文算 signature。
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("data", data);
        params.put("rc4_hash__", encSignature(signPath, signedNonce, params));
        for (Map.Entry<String, String> e : params.entrySet()) {
            e.setValue(encryptRc4(signedNonce, e.getValue()));
        }
        String signature = encSignature(signPath, signedNonce, params);

        LinkedHashMap<String, String> fields = new LinkedHashMap<>(params);
        fields.put("signature", signature);
        fields.put("ssecurity", acc.ssecurity);
        fields.put("_nonce", nonce);

        HttpURLConnection c = open(fullUrl, "POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2");
        c.setRequestProperty("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4");
        c.setRequestProperty("Cookie",
                "userId=" + acc.userId
                        + "; serviceToken=" + acc.serviceToken
                        + "; yetAnotherServiceToken=" + acc.serviceToken
                        + "; locale=zh_CN; timezone=GMT+08:00; is_daylight=0; dst_offset=0; channel=MI_APP_STORE");
        writeForm(c, fields);
        // 响应同样是 RC4 加密（base64），用相同 signedNonce 解出明文 JSON。
        return decryptRc4(signedNonce, readBody(c).trim());
    }

    private static String genNonce() {
        byte[] rnd = new byte[8];
        new SecureRandom().nextBytes(rnd);
        long minutes = System.currentTimeMillis() / 60000L;
        byte[] nonce = new byte[12];
        System.arraycopy(rnd, 0, nonce, 0, 8);
        nonce[8] = (byte) ((minutes >> 24) & 0xFF);
        nonce[9] = (byte) ((minutes >> 16) & 0xFF);
        nonce[10] = (byte) ((minutes >> 8) & 0xFF);
        nonce[11] = (byte) (minutes & 0xFF);
        return Base64.encodeToString(nonce, Base64.NO_WRAP);
    }

    private static String signedNonce(String ssecurity, String nonce) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Base64.decode(ssecurity, Base64.DEFAULT));
        md.update(Base64.decode(nonce, Base64.DEFAULT));
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP);
    }

    /** RC4 加密法签名：SHA1("POST&"+signPath+"&"+各参数 k=v+"&"+signedNonce) 的 base64。 */
    private static String encSignature(String signPath, String signedNonce,
                                       Map<String, String> params) throws Exception {
        List<String> parts = new ArrayList<>();
        parts.add("POST");
        parts.add(signPath);
        for (Map.Entry<String, String> e : params.entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        parts.add(signedNonce);
        String msg = join(parts, "&");
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] sig = md.digest(msg.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(sig, Base64.NO_WRAP);
    }

    /** RC4（ARCFOUR）流加密，先丢弃 1024 字节密钥流再处理数据（对齐开源实现）。 */
    private static byte[] rc4(byte[] key, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("ARCFOUR");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ARCFOUR"));
        c.update(new byte[1024]); // 丢弃前 1024 字节密钥流
        return c.doFinal(data);
    }

    private static String encryptRc4(String signedNonce, String payload) throws Exception {
        byte[] key = Base64.decode(signedNonce, Base64.DEFAULT);
        byte[] enc = rc4(key, payload.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(enc, Base64.NO_WRAP);
    }

    private static String decryptRc4(String signedNonce, String payloadB64) throws Exception {
        byte[] key = Base64.decode(signedNonce, Base64.DEFAULT);
        byte[] dec = rc4(key, Base64.decode(payloadB64, Base64.DEFAULT));
        return new String(dec, StandardCharsets.UTF_8);
    }

    // ============================ HTTP / 工具 ============================

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setRequestProperty("User-Agent", UA);
        if ("POST".equals(method)) {
            c.setDoOutput(true);
        }
        return c;
    }

    private void writeForm(HttpURLConnection c, Map<String, String> fields) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        try (OutputStream os = c.getOutputStream()) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readBody(HttpURLConnection c) throws IOException {
        InputStream is;
        try {
            is = c.getInputStream();
        } catch (IOException e) {
            is = c.getErrorStream();
            if (is == null) {
                throw e;
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private String cookie(String name) {
        for (HttpCookie ck : cookieManager.getCookieStore().getCookies()) {
            if (ck.getName().equals(name)) {
                return ck.getValue();
            }
        }
        return null;
    }

    /** 米家响应以 &&&START&&& 前缀打头，去掉再解析。 */
    private static JSONObject parseMiJson(String body) {
        if (body == null) {
            return new JSONObject();
        }
        String s = body;
        int idx = s.indexOf("&&&START&&&");
        if (idx >= 0) {
            s = s.substring(idx + "&&&START&&&".length());
        }
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String md5Upper(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static String normMac(String mac) {
        return mac == null ? "" : mac.replace(":", "").toUpperCase();
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String randomUpper(int len) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
