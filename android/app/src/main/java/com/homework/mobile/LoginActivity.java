package com.homework.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Deque;

public final class LoginActivity extends Activity {
    private final Deque<WebView> tabs = new ArrayDeque<>();
    private String platform;
    private FrameLayout content;
    private ProgressBar progress;

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        String address = getIntent().getStringExtra("url");
        for (String candidate : Store.PLATFORMS) {
            if (PlatformWeb.allowed(candidate, address)) platform = candidate;
        }
        if (platform == null) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 247, 244));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets system = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(system.left, system.top, system.right, system.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        Button back = new Button(this, null, android.R.attr.borderlessButtonStyle);
        back.setText("返回");
        back.setOnClickListener(view -> exitOrBack());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText(Store.platformName(platform));
        title.setTextColor(Color.rgb(22, 55, 45));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::exitOrBack);
        }
        WebView initial = addTab();
        if (savedState == null || initial.restoreState(savedState) == null) initial.loadUrl(address);
    }

    private WebView addTab() {
        WebView previous = tabs.peekLast();
        if (previous != null) previous.setVisibility(View.GONE);
        WebView web = PlatformWeb.create(this, new Store(this).accountProfile(platform));
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri url = request.getUrl();
                return !"about:blank".equals(url.toString()) && !PlatformWeb.loginAllowed(platform, url.toString());
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                content.removeView(view);
                tabs.remove(view);
                view.destroy();
                Toast.makeText(LoginActivity.this, "网页登录已关闭，请重新打开", Toast.LENGTH_SHORT).show();
                finish();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int amount) {
                if (tabs.peekLast() != view) return;
                progress.setProgress(amount);
                progress.setVisibility(amount == 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message message) {
                if (tabs.size() >= 3) return false;
                ((WebView.WebViewTransport) message.obj).setWebView(addTab());
                message.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (tabs.size() > 1 && tabs.peekLast() == window) closeTab();
            }
        });
        tabs.addLast(web);
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        return web;
    }

    private void closeTab() {
        WebView browser = tabs.removeLast();
        content.removeView(browser);
        browser.stopLoading();
        browser.destroy();
        if (!tabs.isEmpty()) tabs.getLast().setVisibility(View.VISIBLE);
    }

    @Override
    @android.annotation.SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        exitOrBack();
    }

    private void exitOrBack() {
        WebView active = tabs.peekLast();
        if (active != null && active.canGoBack()) {
            active.goBack();
        } else if (tabs.size() > 1) {
            closeTab();
        } else {
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (!tabs.isEmpty()) tabs.getFirst().saveState(out);
    }

    @Override
    protected void onDestroy() {
        while (!tabs.isEmpty()) closeTab();
        super.onDestroy();
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }
}
