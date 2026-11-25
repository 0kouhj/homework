package com.homework.mobile;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class ChaoxingApiLogin {
    private static final String PASSPORT = "https://passport2.chaoxing.com";
    private static final String HOME = "https://i.mooc.chaoxing.com/space/index";
    private static final String DIRECTORY = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1";
    private static final Set<String> LOGIN_HOSTS = Set.of("passport2.chaoxing.com", "i.mooc.chaoxing.com",
            "mooc1-api.chaoxing.com", "mooc1.chaoxing.com", "mooc1-1.chaoxing.com");
    private static final int BODY_LIMIT = 4 * 1024 * 1024;
    // Public form encoding from the platform's fanya/login.js. HTTPS protects the request.
    private static final byte[] FORM_KEY = "u2oh6Vu^HWe4_AES".getBytes(StandardCharsets.UTF_8);

    private final CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final List<SessionCookie> receivedCookies = new ArrayList<>();
    private HttpURLConnection active;
    private boolean closed;

    record SessionCookie(String address, String header) {
        @Override public String toString() { return "SessionCookie[redacted]"; }
    }

    static final class LoginFailure extends IOException {
        final boolean interactive;

        LoginFailure(String issue, boolean interactive) {
            super(issue);
            this.interactive = interactive;
        }
    }

    private record Response(int status, String location, String body) {
        @Override public String toString() { return "Response[status=" + status + "]"; }
    }

    List<SessionCookie> login(AccountVault.Account account)
            throws IOException, JSONException, GeneralSecurityException {
        String loginPage = PASSPORT + "/login?fid=-1&refer=" + encode(HOME);
        String bootstrap = getFollowing(loginPage, true).body();
        verifyBootstrap(bootstrap);
        String encoding = field(bootstrap, "t");
        if (!"true".equals(encoding)) throw new IOException("学习通已更改登录协议");
        String form = form("fid", field(bootstrap, "fid"), "uname", encrypt(account.username()),
                "password", encrypt(account.password()), "refer", field(bootstrap, "refer"),
                "t", encoding, "forbidotherlogin", field(bootstrap, "forbidotherlogin"),
                "validate", "", "doubleFactorLogin", field(bootstrap, "doubleFactorLogin"),
                "independentId", field(bootstrap, "independentId"),
                "independentNameId", field(bootstrap, "independentNameId"));
        Response submission = request(PASSPORT + "/fanyalogin", form, loginPage, true);
        if (submission.status() != 200) throw new JsonHttp.HttpFailure(submission.status());
        getFollowing(acceptedReturn(new JSONObject(submission.body())), false);
        if (!JsonHttp.cookie(cookieHeader(DIRECTORY), "_uid").matches("\\d+")) {
            throw new LoginFailure("学习通未签发可用的登录会话", false);
        }
        Response directory = request(DIRECTORY, null, null, true);
        if (directory.status() != 200) throw new JsonHttp.HttpFailure(directory.status());
        JSONObject data = new JSONObject(directory.body());
        if (data.optInt("result") != 1 || data.optJSONArray("channelList") == null) {
            throw new LoginFailure("学习通课程接口未确认登录会话", false);
        }
        return List.copyOf(receivedCookies);
    }

    static void verifyBootstrap(String bootstrap) throws IOException {
        String verification = field(bootstrap, "needVcode");
        if (!verification.isEmpty() && !"0".equals(verification)) {
            throw interactive("学习通要求完成验证码");
        }
        if (!"0".equals(field(bootstrap, "doubleFactorLogin"))) {
            throw interactive("学习通要求额外身份验证");
        }
    }

    static String acceptedReturn(JSONObject reply) throws IOException, JSONException {
        if (reply.optBoolean("containTwoFactorLogin") || reply.optBoolean("weakpwd")) {
            throw interactive("学习通要求在官网完成额外身份验证或更新密码");
        }
        if (!reply.optBoolean("status")) {
            String detail = reply.optString("msg2");
            if (detail.contains("验证") || detail.contains("安全")) {
                throw interactive("学习通要求在官网完成额外身份验证");
            }
            throw new LoginFailure("学习通登录接口未通过，请检查账号密码或账号状态", false);
        }
        return secureAddress(URLDecoder.decode(reply.getString("url").replace("+", "%2B"), "UTF-8"));
    }

    private Response getFollowing(String start, boolean readBody) throws IOException {
        String address = start;
        for (int i = 0; i < 8; i++) {
            Response response = request(address, null, null, readBody);
            if (response.status() == 200) return response;
            if (response.status() < 300 || response.status() > 399 || response.location() == null) {
                throw new JsonHttp.HttpFailure(response.status());
            }
            address = secureAddress(URI.create(address).resolve(response.location()).toString());
        }
        throw new IOException("学习通登录跳转未完成");
    }

    // Chaoxing can return its main site with an HTTP URL. The session is sent over HTTPS only.
    static String secureAddress(String value) throws IOException {
        String address = value.startsWith("http://") ? "https://" + value.substring(7) : value;
        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException error) {
            throw new IOException("学习通登录跳转地址无效");
        }
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getHost() == null
                || !LOGIN_HOSTS.contains(uri.getHost()) || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IOException("学习通登录跳转地址无效");
        }
        return address;
    }

    static boolean hasSession(android.webkit.CookieManager cookies) {
        String header = cookies.getCookie(DIRECTORY);
        return header != null && JsonHttp.cookie(header, "_uid").matches("\\d+");
    }

    private Response request(String address, String form, String referer, boolean readBody) throws IOException {
        URI uri = URI.create(secureAddress(address));
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        synchronized (this) {
            if (closed) {
                connection.disconnect();
                throw new IOException("登录已取消");
            }
            active = connection;
        }
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            for (var header : jar.get(uri, Map.of()).entrySet()) {
                if (!header.getValue().isEmpty()) connection.setRequestProperty(header.getKey(), String.join("; ", header.getValue()));
            }
            if (form != null) {
                byte[] body = form.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Origin", PASSPORT);
                connection.setRequestProperty("Referer", referer);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (var out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            var headers = connection.getHeaderFields();
            jar.put(uri, headers);
            for (int i = 1; connection.getHeaderFieldKey(i) != null; i++) {
                if ("Set-Cookie".equalsIgnoreCase(connection.getHeaderFieldKey(i))) {
                    receivedCookies.add(new SessionCookie(address, connection.getHeaderField(i)));
                }
            }
            String body = "";
            if (status == 200 && readBody) {
                try (var input = connection.getInputStream()) {
                    body = readText(input);
                }
            }
            return new Response(status, connection.getHeaderField("Location"), body);
        } finally {
            synchronized (this) {
                if (active == connection) active = null;
            }
            connection.disconnect();
        }
    }

    private String cookieHeader(String address) throws IOException {
        return String.join("; ", jar.get(URI.create(address), Map.of()).getOrDefault("Cookie", List.of()));
    }

    void close() {
        HttpURLConnection connection;
        synchronized (this) {
            closed = true;
            connection = active;
            active = null;
        }
        if (connection != null) connection.disconnect();
    }

    private static String readText(InputStream in) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int size;
        while ((size = in.read(bytes)) != -1) {
            if (output.size() + size > BODY_LIMIT) throw new IOException("学习通登录响应过大");
            output.write(bytes, 0, size);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String field(String loginPage, String name) throws IOException {
        var pattern = Pattern.compile("<input\\b[^>]*\\bid=\"" + Pattern.quote(name) + "\"[^>]*\\bvalue=\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE);
        var match = pattern.matcher(loginPage);
        if (!match.find()) throw new IOException("学习通登录协议缺少必要参数");
        return match.group(1);
    }

    private static LoginFailure interactive(String message) {
        return new LoginFailure(message, true);
    }

    private static String encrypt(String text) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(FORM_KEY, "AES"), new IvParameterSpec(FORM_KEY));
        byte[] ciphertext = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    private static String form(String... entries) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < entries.length; i += 2) {
            if (i > 0) result.append('&');
            result.append(encode(entries[i])).append('=').append(encode(entries[i + 1]));
        }
        return result.toString();
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
