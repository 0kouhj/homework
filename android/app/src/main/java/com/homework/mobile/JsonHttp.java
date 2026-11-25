package com.homework.mobile;

import android.webkit.CookieManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class JsonHttp {
    private static final int MAX_BODY = 12 * 1024 * 1024;
    private final CookieManager cookies;
    private HttpURLConnection active;
    private boolean closed;

    JsonHttp(CookieManager cookies) {
        this.cookies = cookies;
    }

    String cookies(String url) {
        String value = cookies.getCookie(url);
        return value == null ? "" : value;
    }

    static String cookie(String header, String name) {
        String prefix = name + "=";
        for (String chunk : header.split(";")) {
            String value = chunk.trim();
            if (value.startsWith(prefix)) return value.substring(prefix.length());
        }
        return "";
    }

    static final class HttpFailure extends IOException {
        final int status;

        HttpFailure(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    JSONObject get(String url) throws IOException, JSONException {
        return request(url, cookies(url), null, null);
    }

    JSONObject postForm(String url, String cookieHeader, String origin, String form) throws IOException, JSONException {
        return request(url, cookieHeader, origin, form.getBytes(StandardCharsets.UTF_8));
    }

    private JSONObject request(String url, String cookieHeader, String origin, byte[] form)
            throws IOException, JSONException {
        HttpURLConnection request = (HttpURLConnection) new URL(url).openConnection();
        synchronized (this) {
            if (closed) {
                request.disconnect();
                throw new IOException("扫描已取消");
            }
            active = request;
        }
        try {
            request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(12_000);
            request.setReadTimeout(12_000);
            request.setRequestProperty("Accept", "application/json");
            if (!cookieHeader.isEmpty()) request.setRequestProperty("Cookie", cookieHeader);
            if (form != null) {
                request.setRequestMethod("POST");
                request.setRequestProperty("Origin", origin);
                request.setRequestProperty("Referer", origin + "/home.htm");
                request.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                request.setDoOutput(true);
                request.setFixedLengthStreamingMode(form.length);
                try (OutputStream out = request.getOutputStream()) {
                    out.write(form);
                }
            }
            int status = request.getResponseCode();
            if (status != 200) throw new HttpFailure(status);
            try (InputStream in = request.getInputStream()) {
                return readJson(in);
            }
        } finally {
            synchronized (this) {
                if (active == request) active = null;
            }
            request.disconnect();
        }
    }

    void close() {
        HttpURLConnection request;
        synchronized (this) {
            closed = true;
            request = active;
            active = null;
        }
        if (request != null) request.disconnect();
    }

    private static JSONObject readJson(InputStream in) throws IOException, JSONException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = in.read(buffer)) != -1) {
            if (bytes.size() + size > MAX_BODY) throw new IOException("接口响应过大");
            bytes.write(buffer, 0, size);
        }
        return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
}

