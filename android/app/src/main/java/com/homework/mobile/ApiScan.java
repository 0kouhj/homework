package com.homework.mobile;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.Consumer;

final class ApiScan {
    private static final long TIMEOUT = 7 * 60_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String platform;
    private final String profile;
    private final JSONArray previousTasks;
    private final Consumer<JSONObject> callback;
    private JsonHttp http;
    private boolean closed;

    ApiScan(String platform, String profile, JSONArray previousTasks, Consumer<JSONObject> callback) {
        this.platform = platform;
        this.profile = profile;
        this.previousTasks = previousTasks;
        this.callback = callback;
    }

    void start() {
        main.post(this::open);
    }

    @SuppressLint("RequiresFeature")
    private void open() {
        if (closed) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            finish(failure("error", "系统 WebView 版本过旧"));
            return;
        }
        JsonHttp transport;
        try {
            var saved = ProfileStore.getInstance().getProfile(profile);
            if (saved == null) {
                finish(failure("login_required", "未能取得平台登录会话"));
                return;
            }
            transport = new JsonHttp(saved.getCookieManager());
        } catch (RuntimeException error) {
            finish(failure("error", "无法读取平台登录会话"));
            return;
        }
        http = transport;
        Thread worker = new Thread(() -> {
            JSONObject result = collect(transport);
            main.post(() -> finish(result));
        }, "agenda-api-scan");
        main.postDelayed(() -> finish(failure("error", "接口扫描超时，已保留本地任务")), TIMEOUT);
        worker.start();
    }

    private JSONObject collect(JsonHttp transport) {
        try {
            if (Store.MOOC.equals(platform)) {
                return MoocScan.collect(new MoocRpc(transport)::read, previousTasks, System.currentTimeMillis());
            }
            return ChaoxingScan.collect(transport, previousTasks);
        } catch (RuntimeException error) {
            return failure("error", "无法读取平台接口数据");
        }
    }

    private void finish(JSONObject result) {
        if (closed) return;
        close();
        callback.accept(result);
    }

    void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (http != null) http.close();
    }

    static JSONObject failure(String status, String issue) {
        return result(status, new JSONArray(), new JSONArray().put(issue));
    }

    static JSONObject result(String status, JSONArray tasks, JSONArray issues) {
        try {
            return new JSONObject().put("status", status).put("tasks", tasks).put("issues", issues);
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }
}

