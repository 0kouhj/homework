package com.homework.mobile;

import android.net.Uri;
import android.content.Context;
import android.annotation.SuppressLint;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.Locale;

final class PlatformWeb {
    private PlatformWeb() {}

    static String home(String platform) {
        if (Store.CHAOXING.equals(platform)) return "https://i.mooc.chaoxing.com/space/index";
        return "https://www.icourse163.org/home.htm#/home/course";
    }

    static boolean allowed(String platform, String address) {
        if (address == null || address.isEmpty()) return false;
        Uri uri = Uri.parse(address);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        host = host.toLowerCase(Locale.ROOT);
        return Store.CHAOXING.equals(platform) ? domain(host, "chaoxing.com")
                : Store.MOOC.equals(platform) && domain(host, "icourse163.org");
    }

    static boolean loginAllowed(String platform, String address) {
        if (allowed(platform, address)) return true;
        Uri uri = Uri.parse(address);
        if (!Store.MOOC.equals(platform) || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        String host = uri.getHost();
        return host != null && domain(host.toLowerCase(Locale.ROOT), "163.com");
    }

    private static boolean domain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    @SuppressLint("RequiresFeature")
    static WebView create(Context context, String profile) {
        if (profile != null && !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            throw new IllegalStateException("系统 WebView 版本过旧");
        }
        PasswordLogin.cleanUnusedProfiles(context);
        WebView view = new WebView(context);
        WebView.setWebContentsDebuggingEnabled(false);
        if (profile != null) WebViewCompat.setProfile(view, profile);
        configure(view);
        return view;
    }

    static void layoutForScan(WebView view) {
        float density = view.getResources().getDisplayMetrics().density;
        int width = (int) (1180 * density);
        int height = (int) (820 * density);
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    static void configure(WebView web) {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        String androidAgent = settings.getUserAgentString();
        int chrome = androidAgent.indexOf("Chrome/");
        String version = chrome < 0 ? "131.0.0.0" : androidAgent.substring(chrome + 7).split(" ")[0];
        settings.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + version + " Safari/537.36");
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
    }
}
