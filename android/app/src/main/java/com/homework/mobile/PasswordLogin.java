package com.homework.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class PasswordLogin {
    private static final String MOOC_HOME = "https://www.icourse163.org";
    private static final String MOOC_FORM = "https://reg.icourse163.org";
    private static final Set<String> MOOC_ORIGINS = Set.of(MOOC_HOME, MOOC_FORM);
    private static final long LOGIN_TIMEOUT = 65_000;
    private static boolean cleanedProfiles;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final String platform;
    private final String profile;
    private final Consumer<String> completion;
    private AccountVault.Account account;
    private ChaoxingApiLogin nativeLogin;
    private WebView web;
    private boolean closed;
    private boolean supplied;
    private boolean submitted;
    private boolean confirming;

    PasswordLogin(Context context, String platform, AccountVault.Account account, String profile,
                  Consumer<String> completion) {
        this.context = context.getApplicationContext();
        this.platform = platform;
        this.account = account;
        this.profile = profile;
        this.completion = completion;
    }

    static String newProfile() {
        return "agenda-" + UUID.randomUUID();
    }

    static boolean supported(String platform) {
        return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                && (Store.CHAOXING.equals(platform)
                || (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)));
    }

    @SuppressLint("RequiresFeature")
    static void deleteProfile(String name) {
        if (name == null || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return;
        try {
            ProfileStore.getInstance().deleteProfile(name);
        } catch (IllegalStateException ignored) {
            // Chromium retains loaded profiles until the process exits.
            // Remove session data now and delete the profile at the next cold start.
            var profile = ProfileStore.getInstance().getProfile(name);
            if (profile != null) {
                var cookies = profile.getCookieManager();
                cookies.removeAllCookies(removed -> cookies.flush());
                profile.getWebStorage().deleteAllData();
            }
        }
    }

    @SuppressLint("RequiresFeature")
    static void cleanUnusedProfiles(Context context) {
        if (cleanedProfiles || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return;
        cleanedProfiles = true;
        Store store = new Store(context);
        for (String name : ProfileStore.getInstance().getAllProfileNames()) {
            if (name.startsWith("agenda-") && Store.PLATFORMS.stream()
                    .noneMatch(platform -> name.equals(store.accountProfile(platform)))) {
                deleteProfile(name);
            }
        }
    }

    void start() {
        main.post(this::open);
    }

    @SuppressLint("RequiresFeature")
    private void open() {
        if (closed) return;
        if (!supported(platform) || profile == null) {
            finish("系统 WebView 版本过旧，请更新后重试");
            return;
        }
        if (Store.CHAOXING.equals(platform)) {
            openNative();
            return;
        }
        try {
            String javascript = readLoginScript();
            web = PlatformWeb.create(context, profile);
            PlatformWeb.layoutForScan(web);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return request.isForMainFrame() && !MOOC_ORIGINS.contains(origin(request.getUrl()));
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) main.post(() -> finish("无法连接平台官网，请检查网络"));
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse error) {
                    if (request.isForMainFrame()) main.post(() -> finish("平台官网返回 HTTP " + error.getStatusCode()));
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    main.post(() -> finish("网页登录窗口已关闭"));
                    return true;
                }
            });
            WebViewCompat.addWebMessageListener(web, "agendaAuth", MOOC_ORIGINS, (view, message, source, top, reply) -> {
                if (closed || message.getType() != androidx.webkit.WebMessageCompat.TYPE_STRING || message.getData() == null || message.getData().length() > 512) return;
                try {
                    JSONObject event = new JSONObject(message.getData());
                    String route = event.optString("path");
                    String sender = origin(source);
                    String type = event.optString("event");
                    boolean trustedForm = MOOC_FORM.equals(sender) && !top
                            && route.matches("(?:/webzj/v[0-9.]+)?/pub/index_dl2_new[.]html");
                    if ("ready".equals(type) && trustedForm && !supplied) {
                        JSONObject response = new JSONObject().put("username", account.username())
                                .put("password", account.password());
                        supplied = true;
                        reply.postMessage(response.toString());
                    } else if ("submitted".equals(type) && trustedForm && supplied) {
                        submitted = true;
                    } else if ("verified".equals(type) && top && MOOC_HOME.equals(sender) && "/home.htm".equals(route)
                            && submitted && !confirming) {
                        confirming = true;
                        main.postDelayed(() -> finish(null), 550);
                    }
                } catch (JSONException ignored) {
                    // Only the fixed event schema is handled.
                }
            });
            WebViewCompat.addDocumentStartJavaScript(web, "(() => { const agendaEmail = "
                    + account.username().contains("@") + ";\n" + javascript + "\n})();", MOOC_ORIGINS);
            var cookies = WebViewCompat.getProfile(web).getCookieManager();
            main.postDelayed(() -> finish(submitted ? "平台未确认登录，可能需要验证码或额外验证"
                    : supplied ? "官网登录表单未能提交，请稍后重试" : "官网账号密码表单未加载完成，请稍后重试"), LOGIN_TIMEOUT);
            cookies.removeAllCookies(ignored -> {
                if (closed) return;
                cookies.flush();
                web.loadUrl(MOOC_HOME + "/home.htm");
            });
        } catch (IOException | RuntimeException failure) {
            finish("无法启动官网登录验证");
        }
    }

    private void openNative() {
        var client = new ChaoxingApiLogin();
        nativeLogin = client;
        AccountVault.Account credentials = account;
        main.postDelayed(() -> finish("学习通登录接口超时"), LOGIN_TIMEOUT);
        new Thread(() -> {
            try {
                List<ChaoxingApiLogin.SessionCookie> session = client.login(credentials);
                main.post(() -> saveSession(session));
            } catch (ChaoxingApiLogin.LoginFailure error) {
                main.post(() -> finish(error.getMessage()));
            } catch (JsonHttp.HttpFailure error) {
                main.post(() -> finish("学习通登录接口返回 HTTP " + error.status));
            } catch (IOException | GeneralSecurityException | JSONException | RuntimeException error) {
                main.post(() -> finish("学习通未能通过登录接口建立会话"));
            }
        }, "agenda-chaoxing-login").start();
    }

    @SuppressLint("RequiresFeature")
    private void saveSession(List<ChaoxingApiLogin.SessionCookie> session) {
        if (closed) return;
        try {
            cleanUnusedProfiles(context);
            CookieManager cookies = ProfileStore.getInstance().getOrCreateProfile(profile).getCookieManager();
            cookies.removeAllCookies(ignored -> writeSession(cookies, session));
        } catch (RuntimeException error) {
            finish("无法保存新签发的学习通会话");
        }
    }

    private void writeSession(CookieManager cookies, List<ChaoxingApiLogin.SessionCookie> session) {
        if (closed) return;
        int[] pending = { session.size() };
        try {
            for (ChaoxingApiLogin.SessionCookie cookie : session) {
                cookies.setCookie(cookie.address(), cookie.header(), accepted -> {
                    if (--pending[0] == 0 && !closed) {
                        try {
                            cookies.flush();
                            finish(ChaoxingApiLogin.hasSession(cookies) ? null : "无法保存新签发的学习通会话");
                        } catch (RuntimeException error) {
                            finish("无法保存新签发的学习通会话");
                        }
                    }
                });
            }
        } catch (RuntimeException error) {
            finish("无法保存新签发的学习通会话");
        }
    }

    private String readLoginScript() throws IOException {
        try (InputStream input = context.getAssets().open("password-login.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String origin(Uri uri) {
        if (uri.getHost() == null || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) return "";
        return "https://" + uri.getHost();
    }

    private void finish(String issue) {
        if (closed) return;
        close();
        completion.accept(issue);
    }

    void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        account = null;
        if (nativeLogin != null) {
            nativeLogin.close();
            nativeLogin = null;
        }
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
    }
}
