package com.homework.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AccountDialog {
    private final Activity activity;
    private final Store store;
    private final String platform;
    private final Runnable changed;
    private final AccountVault vault;
    private EditText username;
    private EditText password;
    private TextView status;
    private AlertDialog dialog;
    private Button verify;
    private Button save;
    private AccountVault.Account saved;
    private AccountVault.Account verified;
    private String verifiedProfile;
    private PasswordLogin login;

    AccountDialog(Activity activity, Store store, String platform, Runnable changed) {
        this.activity = activity;
        this.store = store;
        this.platform = platform;
        this.changed = changed;
        this.vault = new AccountVault(activity);
    }

    void show() {
        String firstStatus = "填写账号密码后，可以先验证登录。";
        try {
            saved = vault.read(platform);
            if (saved != null) firstStatus = "留空密码可继续使用本机已保存的密码。";
        } catch (IllegalStateException failure) {
            firstStatus = "无法读取已保存的凭据，请重新填写或清除。";
        }
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(10), dp(24), dp(4));

        username = new EditText(activity);
        username.setSingleLine(true);
        username.setSaveEnabled(false);
        username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        username.setHint(Store.CHAOXING.equals(platform) ? "手机号或超星号" : "手机号或邮箱");
        if (saved != null) username.setText(saved.username());
        form.addView(username);

        password = new EditText(activity);
        password.setSingleLine(true);
        password.setSaveEnabled(false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint(saved == null ? "密码" : "密码已保存，留空沿用");
        password.setTypeface(Typeface.DEFAULT);
        form.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        form.addView(password);

        status = new TextView(activity);
        status.setPadding(0, dp(8), 0, dp(4));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText(firstStatus);
        form.addView(status);

        if (saved != null || store.accountProfile(platform) != null) {
            Button clear = new Button(activity, null, android.R.attr.borderlessButtonStyle);
            clear.setText("清除本机账号");
            clear.setOnClickListener(view -> confirmClear());
            form.addView(clear);
        }

        dialog = new AlertDialog.Builder(activity)
                .setTitle(Store.platformName(platform) + "账号")
                .setView(form).setNegativeButton("取消", null)
                .setNeutralButton("验证登录", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnDismissListener(ignored -> {
            if (login != null) login.close();
            releaseVerification();
            password.getText().clear();
            saved = null;
        });
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        verify = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        verify.setOnClickListener(view -> verifyAccount());
        save.setOnClickListener(view -> saveAccount());
    }

    private AccountVault.Account entered() {
        String user = username.getText().toString().trim();
        String pass = password.getText().toString();
        if (user.isEmpty()) {
            username.setError("请输入账号");
            return null;
        }
        if (pass.isEmpty() && saved != null && saved.username().equals(user)) pass = saved.password();
        if (pass.isEmpty()) {
            password.setError("请输入密码");
            return null;
        }
        return new AccountVault.Account(user, pass);
    }

    private void verifyAccount() {
        AccountVault.Account account = entered();
        if (account == null) return;
        releaseVerification();
        String profile = PasswordLogin.newProfile();
        status.setText("正在向平台官网验证…");
        setBusy(true);
        login = new PasswordLogin(activity, platform, account, profile, issue -> {
            login = null;
            setBusy(false);
            if (issue == null) {
                verified = account;
                verifiedProfile = profile;
                status.setText("验证通过。点「保存」后即可扫描。");
            } else {
                verifiedProfile = null;
                PasswordLogin.deleteProfile(profile);
                status.setText(issue);
            }
        });
        verifiedProfile = profile;
        login.start();
    }

    private void setBusy(boolean busy) {
        username.setEnabled(!busy);
        password.setEnabled(!busy);
        verify.setEnabled(!busy);
        save.setEnabled(!busy);
    }

    private void releaseVerification() {
        PasswordLogin.deleteProfile(verifiedProfile);
        verified = null;
        verifiedProfile = null;
    }

    private void saveAccount() {
        AccountVault.Account account = entered();
        if (account == null) return;
        if (!PasswordLogin.supported(platform)) {
            status.setText("系统 WebView 版本过旧，请更新后重试。");
            return;
        }
        try {
            String old = store.accountProfile(platform);
            String profile = account.equals(verified) ? verifiedProfile
                    : account.equals(saved) && old != null ? old : PasswordLogin.newProfile();
            boolean switchAccount = saved == null || !saved.username().equals(account.username());
            vault.save(platform, account);
            store.applyAccount(platform, profile, switchAccount);
            if (profile.equals(verifiedProfile)) verifiedProfile = null;
            if (!profile.equals(old)) PasswordLogin.deleteProfile(old);
            ScanJob.configure(activity);
            ScanJob.accountChanged(activity);
            Reminders.schedule(activity);
            changed.run();
            dialog.dismiss();
        } catch (IllegalStateException failure) {
            status.setText("保存账号失败，请检查设备安全设置后重试。");
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity).setMessage("清除该平台在本机保存的账号和任务？")
                .setNegativeButton("取消", null).setPositiveButton("清除", (prompt, button) -> {
                    try {
                        vault.clear(platform);
                        String old = store.accountProfile(platform);
                        store.applyAccount(platform, null, true);
                        PasswordLogin.deleteProfile(old);
                        Reminders.schedule(activity);
                        changed.run();
                        dialog.dismiss();
                    } catch (IllegalStateException failure) {
                        status.setText("无法清除账号，请重试。");
                    }
                }).show();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
