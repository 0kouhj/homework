package com.homework.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int PAPER = 0xfff4f7f4, WHITE = Color.WHITE;
    private static final int INK = 0xff19342b, MUTED = 0xff50665b, ACCENT = 0xff235849;
    private static final int COURSE_SOURCE = 0xffd7dedb;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM 月 dd 日 HH:mm").withZone(Store.BEIJING);
    private final Map<String, TextView> platformStatuses = new LinkedHashMap<>();
    private Store store;
    private ScrollView scroll;
    private LinearLayout root;
    private LinearLayout tasks;
    private TextView summary;
    private Button scanButton;
    private TextView permissions;
    private boolean inSettings;
    private TaskFilter taskFilter = TaskFilter.UPCOMING;
    private boolean registered;
    private android.window.OnBackInvokedCallback back;
    private int hour;
    private int minute;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshTasks(); }
    };

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);
        if (saved != null) {
            TaskFilter[] choices = TaskFilter.values();
            int selected = saved.getInt("task_filter",
                    saved.getBoolean("all") ? TaskFilter.ALL.ordinal() : TaskFilter.UPCOMING.ordinal());
            if (selected >= 0 && selected < choices.length) taskFilter = choices[selected];
        }
        renderHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ScanJob.ACTION_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacyUpdateReceiver(filter);
        registered = true;
        refreshTasks();
        Reminders.schedule(this);
        updatePermissionStatus();
        ScanJob.configure(this);
        ScanJob.catchUp(this);
    }

    // Android 26–32 use the signature permission for the same app-only broadcast.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyUpdateReceiver(IntentFilter filter) {
        registerReceiver(updates, filter, ScanJob.UPDATE_PERMISSION, null);
    }

    @Override
    protected void onPause() {
        if (registered) unregisterReceiver(updates);
        registered = false;
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putInt("task_filter", taskFilter.ordinal());
        super.onSaveInstanceState(out);
    }

    @Override
    @android.annotation.SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        if (inSettings) renderHome();
        else super.onBackPressed();
    }

    private void frame() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(edges.left, edges.top, edges.right, edges.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        root = column();
        root.setPadding(dp(20), dp(12), dp(20), dp(30));
        scroll.addView(root);
        setContentView(scroll);
    }

    private void renderHome() {
        inSettings = false;
        if (Build.VERSION.SDK_INT >= 33 && back != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
            back = null;
        }
        platformStatuses.clear();
        frame();
        LinearLayout header = row();
        TextView title = label("课业日程", 29, INK, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(action("设置", false, () -> renderSettings()));
        root.addView(header);

        LinearLayout overview = card(root, 10);
        overview.setBackground(rounded(ACCENT));
        overview.addView(label("任务概览", 16, WHITE, true));
        summary = label("", 15, 0xffd7ede1, false);
        overview.addView(summary);
        scanButton = action("立即扫描", false, () -> {
            if (!store.hasConnectedPlatforms()) {
                toast("请先登录至少一个平台");
            } else if (ScanJob.runNow(this)) {
                refreshTasks();
            } else {
                toast("系统未接受扫描请求，请稍后重试");
            }
        });
        scanButton.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        overview.addView(scanButton, new LinearLayout.LayoutParams(-1, dp(48)));

        section("平台连接");
        LinearLayout accounts = card(root, 6);
        TypedValue accountBackground = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, accountBackground, true);
        for (String platform : Store.PLATFORMS) {
            LinearLayout line = row();
            LinearLayout copy = column();
            copy.addView(label(Store.platformName(platform), 16, INK, true));
            TextView status = label("", 13, MUTED, false);
            platformStatuses.put(platform, status);
            copy.addView(status);
            line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            Button account = action("账号", false,
                    () -> new AccountDialog(this, store, platform, this::refreshTasks).show());
            account.setBackgroundResource(accountBackground.resourceId);
            account.setTextSize(18);
            account.setTypeface(account.getTypeface(), Typeface.BOLD);
            line.addView(account);
            accounts.addView(line);
        }

        LinearLayout heading = row();
        heading.addView(label("课程任务", 19, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button filter = action(taskFilter.label() + " ▾", false, () -> {});
        filter.setOnClickListener(view -> showTaskFilters(filter));
        heading.addView(filter);
        LinearLayout.LayoutParams top = new LinearLayout.LayoutParams(-1, -2);
        top.topMargin = dp(14);
        root.addView(heading, top);
        tasks = column();
        root.addView(tasks);
        refreshTasks();
    }

    private void showTaskFilters(Button anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        TaskFilter[] choices = TaskFilter.values();
        for (TaskFilter choice : choices) {
            popup.getMenu().add(1, choice.ordinal(), choice.ordinal(), choice.label());
        }
        popup.getMenu().setGroupCheckable(1, true, true);
        popup.getMenu().findItem(taskFilter.ordinal()).setChecked(true);
        popup.setOnMenuItemClickListener(item -> {
            taskFilter = choices[item.getItemId()];
            anchor.setText(taskFilter.label() + " ▾");
            refreshTasks();
            return true;
        });
        popup.show();
    }

    private void refreshTasks() {
        if (inSettings || tasks == null) return;
        boolean busy = ScanJob.busy();
        scanButton.setText(busy ? "扫描中…" : "立即扫描");
        scanButton.setEnabled(!busy);
        for (var platform : platformStatuses.entrySet()) {
            String progress = ScanJob.progress(platform.getKey());
            platform.getValue().setText(progress == null ? store.platformStatus(platform.getKey()) : progress);
        }
        tasks.removeAllViews();
        List<Store.Task> rows = store.tasks();
        rows.sort(TaskFilter.ORDER);
        Instant now = Instant.now();
        Instant cutoff = now.atZone(Store.BEIJING).minusYears(1).toInstant();
        Map<String, LinearLayout> courses = new LinkedHashMap<>();
        int active = 0;
        for (Store.Task task : rows) {
            Instant due = task.dueInstant();
            Instant opens = task.opensInstant();
            if (!Store.CHAOXING.equals(task.platform()) && opens != null && !opens.isAfter(cutoff)) continue;
            boolean completed = task.completed();
            boolean pending = TaskFilter.isUpcoming(completed, due, now);
            if (pending) active++;
            if (!taskFilter.includes(pending, completed, due)) continue;
            String key = task.courseKey();
            LinearLayout course = courses.get(key);
            if (course == null) {
                course = card(tasks, 8);
                course.setPadding(0, 0, 0, dp(11));
                course.setClipToOutline(true);
                LinearLayout courseHeading = row();
                courseHeading.setBackgroundColor(ACCENT);
                courseHeading.setPadding(dp(15), dp(6), dp(19), dp(6));
                LinearLayout courseDetails = column();
                courseDetails.addView(label(task.course(), 17, WHITE, true));
                courseDetails.addView(label(Store.platformName(task.platform()), 12, COURSE_SOURCE, false));
                courseHeading.addView(courseDetails, new LinearLayout.LayoutParams(0, -2, 1));
                ImageButton deleteCourse = deleteAction("删除课程", task.course(), () -> store.hideCourse(task));
                deleteCourse.setColorFilter(COURSE_SOURCE);
                courseHeading.addView(deleteCourse);
                course.addView(courseHeading);
                courses.put(key, course);
            }
            String kind = switch (task.kind()) {
                case "exam" -> "考试";
                case "quiz" -> "测验";
                default -> "作业";
            };
            String date = due == null ? "截止时间未知"
                    : task.due().length() == 10 ? task.due() + " 截止"
                    : ("estimated".equals(task.precision()) ? "预计 " : "") + TIME.format(due) + " 截止";
            LinearLayout taskRow = row();
            taskRow.setPadding(dp(19), dp(12), dp(19), dp(8));
            LinearLayout block = column();
            block.addView(label(task.title() + (task.url().isEmpty() ? "" : "  ↗"), 16, INK, true));
            String detail = task.status().isEmpty() ? "": " · " + task.status();
            block.addView(label(kind + " · " + date + detail, 13, MUTED, false));
            if (PlatformWeb.allowed(task.platform(), task.url())) {
                block.setOnClickListener(view -> startActivity(
                        new Intent(this, LoginActivity.class).putExtra("url", task.url())));
                block.setFocusable(true);
            }
            taskRow.addView(block, new LinearLayout.LayoutParams(0, -2, 1));
            taskRow.addView(deleteAction("删除任务", task.title(), () -> store.hideTask(task)));
            course.addView(taskRow);
        }
        summary.setText(getString(R.string.pending_count, active));
        if (courses.isEmpty()) {
            card(tasks, 8).addView(label(taskFilter.emptyMessage(), 15, MUTED, false));
        }
    }

    private ImageButton deleteAction(String label, String name, Runnable hide) {
        ImageButton button = new ImageButton(this);
        TypedValue background = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
        button.setBackgroundResource(background.resourceId);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setPadding(0, 0, 0, 0);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(R.drawable.ic_delete);
        button.setContentDescription(label + " " + name);
        button.setTooltipText(label);
        button.setOnClickListener(view -> {
            hide.run();
            Reminders.schedule(this);
            refreshTasks();
        });
        return button;
    }

    private void showBlacklist() {
        ScrollView body = new ScrollView(this);
        LinearLayout entries = column();
        entries.setPadding(dp(24), 0, dp(16), dp(8));
        body.addView(entries);

        renderBlacklist(entries);
        new AlertDialog.Builder(this)
                .setTitle("黑名单")
                .setView(body)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void renderBlacklist(LinearLayout panel) {
        panel.removeAllViews();
        List<Store.BlacklistEntry> entries = store.blacklist();
        if (entries.isEmpty()) {
            TextView empty = label("黑名单为空", 15, MUTED, false);
            empty.setPadding(0, dp(12), 0, dp(8));
            panel.addView(empty);
        }
        blacklistSection(panel, entries, true);
        blacklistSection(panel, entries, false);
    }

    private void blacklistSection(LinearLayout panel, List<Store.BlacklistEntry> entries,
                                  boolean courses) {
        boolean started = false;
        for (Store.BlacklistEntry entry : entries) {
            if (entry.course() != courses) continue;
            if (!started) {
                TextView heading = label(courses ? "课程" : "任务", 16, INK, true);
                heading.setPadding(0, dp(12), 0, dp(4));
                panel.addView(heading);
                started = true;
            }

            LinearLayout item = row();
            LinearLayout details = column();
            details.addView(label(entry.name(), 15, INK, false));
            String description = Store.platformName(entry.platform());
            if (!entry.course() && !entry.courseName().isEmpty()) {
                description += " · " + entry.courseName();
            }
            details.addView(label(description, 12, MUTED, false));
            item.addView(details, new LinearLayout.LayoutParams(0, -2, 1));

            Button restore = new Button(this, null, android.R.attr.borderlessButtonStyle);
            restore.setText("恢复");
            restore.setTextColor(ACCENT);
            restore.setContentDescription("恢复 " + entry.name());
            restore.setOnClickListener(view -> {
                store.restore(entry);
                Reminders.schedule(this);
                renderBlacklist(panel);
            });
            item.addView(restore);
            panel.addView(item);
        }
    }

    private void renderSettings() {
        inSettings = true;
        if (Build.VERSION.SDK_INT >= 33 && back == null) {
            back = this::renderHome;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
        }
        frame();
        LinearLayout header = row();
        header.addView(label("扫描与提醒", 26, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams backButtonLayout = new LinearLayout.LayoutParams(-2, -2);
        backButtonLayout.setMarginStart(dp(10));
        header.addView(action("返回", false, this::renderHome), backButtonLayout);
        root.addView(header);
        root.addView(action("黑名单", false, this::showBlacklist), settingsButtonLayout());

        Store.Settings current = store.settings();
        hour = current.hour();
        minute = current.minute();
        LinearLayout scan = card(root, 12);
        Switch background = toggle(scan, "后台扫描", current.background());
        scan.addView(label("扫描间隔（分钟，15–1440）", 14, MUTED, false));
        EditText interval = number(scan, current.interval(), "扫描间隔（分钟）");
        scan.addView(label("Android 可能延后后台扫描。打开应用时，如果缓存已超过扫描间隔，应用会联网补扫。", 13, MUTED, false));

        LinearLayout alerts = card(root, 12);
        Switch daily = toggle(alerts, "每日待办汇总", current.daily());
        Button time = action(timeLabel(), false, () -> {});
        time.setOnClickListener(view -> new TimePickerDialog(this, (picker, h, m) -> {
            hour = h;
            minute = m;
            time.setText(timeLabel());
        }, hour, minute, true).show());
        LinearLayout.LayoutParams timeLayout = settingsButtonLayout();
        timeLayout.bottomMargin = dp(10);
        alerts.addView(time, timeLayout);
        Switch deadline = toggle(alerts, "截止前提醒", current.deadline());
        alerts.addView(label("提前分钟数（0–10080，0 表示截止时）", 14, MUTED, false));
        EditText lead = number(alerts, current.lead(), "截止前提醒分钟数");

        root.addView(action("保存设置", true, () -> {
            Integer mins = readNumber(interval, 15, 1440);
            Integer before = readNumber(lead, 0, 10080);
            if (mins == null || before == null) return;
            store.saveSettings(new Store.Settings(background.isChecked(), mins, daily.isChecked(),
                    hour, minute, deadline.isChecked(), before));
            ScanJob.configure(this);
            ScanJob.catchUp(this);
            Reminders.schedule(this);
            toast("设置已保存");
            renderHome();
        }), settingsButtonLayout());

        LinearLayout system = card(root, 10);
        permissions = label("", 13, MUTED, false);
        system.addView(permissions);
        system.addView(action("通知权限", false, this::notificationSettings), settingsButtonLayout());
        if (Build.VERSION.SDK_INT >= 31) {
            system.addView(action("精确提醒权限", false, () -> openSettings(
                    new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", getPackageName(), null)))),
                    settingsButtonLayout());
        }
        system.addView(action("系统应用设置", false, () -> openSettings(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)))), settingsButtonLayout());
        if ("Xiaomi".equalsIgnoreCase(Build.MANUFACTURER)) {
            system.addView(label(getString(R.string.miui_autostart_help), 13, MUTED, false));
            system.addView(action(getString(R.string.miui_autostart_settings), false, () -> openSettings(
                    new Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))),
                    settingsButtonLayout());
        }
        system.addView(action("1 分钟后试提醒", false, () -> toast(Reminders.scheduleTest(this)
                ? getString(R.string.test_reminder_scheduled) : getString(R.string.test_reminder_blocked))),
                settingsButtonLayout());
        updatePermissionStatus();
    }

    private LinearLayout.LayoutParams settingsButtonLayout() {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(10);
        return layout;
    }

    private void notificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && !getPreferences(0).getBoolean("asked_for_notifications", false)) {
            getPreferences(0).edit().putBoolean("asked_for_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 21);
        } else openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] requested, int[] results) {
        super.onRequestPermissionsResult(requestCode, requested, results);
        updatePermissionStatus();
        Reminders.schedule(this);
    }

    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException ignored) { toast("系统未提供此权限入口"); }
    }

    private void updatePermissionStatus() {
        if (!inSettings || permissions == null) return;
        boolean granted = getSystemService(NotificationManager.class).areNotificationsEnabled();
        String notification = getString(granted ? R.string.notification_on : R.string.notification_off);
        String exact = getString(Reminders.canScheduleExact(this) ? R.string.exact_on : R.string.exact_off);
        boolean daily = Reminders.channelEnabled(this, Reminders.DAILY_CHANNEL);
        boolean deadline = Reminders.channelEnabled(this, Reminders.DEADLINE_CHANNEL);
        String channels = getString(R.string.reminder_channels,
                getString(daily ? R.string.channel_on : R.string.channel_off),
                getString(deadline ? R.string.channel_on : R.string.channel_off));
        String restriction = getString(R.string.background_status_unknown);
        if (Build.VERSION.SDK_INT >= 28) {
            boolean restricted = getSystemService(ActivityManager.class).isBackgroundRestricted();
            restriction = getString(restricted ? R.string.background_status_restricted
                    : R.string.background_status_clear);
        }
        permissions.setText(getString(R.string.permission_status, notification, channels, exact, restriction));
    }

    private String timeLabel() {
        return String.format(Locale.ROOT, "汇总时间  %02d:%02d", hour, minute);
    }

    private Integer readNumber(EditText field, int min, int max) {
        try {
            int value = Integer.parseInt(field.getText().toString());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) {}
        field.setError("请输入 " + min + "–" + max + " 的整数");
        return null;
    }

    private EditText number(LinearLayout parent, int value, String description) {
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setContentDescription(description);
        edit.setText(String.valueOf(value));
        edit.setSingleLine(true);
        parent.addView(edit, new LinearLayout.LayoutParams(-1, dp(48)));
        return edit;
    }

    private Switch toggle(LinearLayout parent, String name, boolean checked) {
        Switch widget = new Switch(this);
        widget.setText(name);
        widget.setTextSize(16);
        widget.setTextColor(INK);
        widget.setChecked(checked);
        widget.setMinHeight(dp(48));
        parent.addView(widget, new LinearLayout.LayoutParams(-1, -2));
        return widget;
    }

    private void section(String value) {
        TextView label = label(value, 19, INK, true);
        label.setPadding(0, dp(18), 0, 0);
        root.addView(label);
    }

    private LinearLayout card(LinearLayout parent, int margin) {
        LinearLayout panel = column();
        panel.setBackground(rounded(WHITE));
        panel.setPadding(dp(15), dp(11), dp(15), dp(11));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(panel, params);
        return panel;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private Button action(String text, boolean main, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(main ? WHITE : ACCENT);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setBackground(rounded(main ? ACCENT : 0xffdeede5));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(15));
        return background;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(null, Typeface.BOLD);
        return label;
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
