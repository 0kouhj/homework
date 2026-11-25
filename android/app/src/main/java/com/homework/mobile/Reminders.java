package com.homework.mobile;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Reminders extends BroadcastReceiver {
    private static final String DAILY = "com.homework.mobile.DAILY";
    private static final String DEADLINE = "com.homework.mobile.DEADLINE";
    private static final String TEST = "com.homework.mobile.TEST_REMINDER";
    static final String DAILY_CHANNEL = "daily";
    static final String DEADLINE_CHANNEL = "deadline";
    private static final String DAILY_SENT = "daily_on_";
    private static final String DEADLINE_TASKS = "deadline_tasks";
    private static final String DEADLINE_LEAD = "deadline_lead";
    private static final String TEST_WHEN = "test_when";
    private static final Duration LATE_WINDOW = Duration.ofHours(2);
    private static final DateTimeFormatter DUE_DISPLAY =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(Store.BEIJING);

    static boolean canScheduleExact(Context context) {
        return Build.VERSION.SDK_INT < 31 ||
                context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static void schedule(Context context) {
        schedule(context, Instant.now(), ZoneId.systemDefault());
    }

    static void schedule(Context context, Instant now, ZoneId zone) {
        createChannels(context);
        Store store = new Store(context);
        SharedPreferences state = alarmState(context);
        SharedPreferences sent = history(context);
        prune(sent, now);
        scheduleDaily(context, store, state, sent, now, zone);
        scheduleDeadlines(context, store, state, sent, now);
    }

    private static void scheduleDaily(Context context, Store store, SharedPreferences state,
                                      SharedPreferences sent, Instant now, ZoneId zone) {
        Store.Settings settings = store.settings();
        if (!settings.daily()) {
            cancel(context, DAILY);
            state.edit().remove(DAILY).remove(DAILY + ".at").apply();
            return;
        }
        LocalDate date = now.atZone(zone).toLocalDate();
        LocalTime time = LocalTime.of(settings.hour(), settings.minute());
        Instant today = date.atTime(time).atZone(zone).toInstant();
        String key = DAILY_SENT + date;
        long planned = state.getLong(DAILY + ".at", 0);
        boolean delivered = sent.contains(key);
        if (!delivered && planned == today.toEpochMilli() && !today.isAfter(now)
                && !now.isAfter(today.plus(LATE_WINDOW))) {
            if (!sendDaily(context, store, now)) {
                cancel(context, DAILY);
                return;
            }
            sent.edit().putLong(key, now.toEpochMilli()).apply();
            delivered = true;
        }
        if (!enabled(context, DAILY_CHANNEL)) {
            cancel(context, DAILY);
            return;
        }
        Instant next = delivered ? date.plusDays(1).atTime(time).atZone(zone).toInstant()
                : nextDaily(settings, now, zone);
        arm(context, DAILY, next);
        state.edit().remove(DAILY).putLong(DAILY + ".at", next.toEpochMilli()).apply();
    }

    private static void scheduleDeadlines(Context context, Store store, SharedPreferences state,
                                          SharedPreferences sent, Instant now) {
        Store.Settings settings = store.settings();
        if (!settings.deadline()) {
            clearDeadlines(context, state);
            return;
        }
        int lead = settings.lead();
        long planned = state.getLong(DEADLINE + ".at", Long.MAX_VALUE);
        Set<String> plannedTasks = state.getInt(DEADLINE_LEAD, -1) == lead
                ? state.getStringSet(DEADLINE_TASKS, Set.of()) : Set.of();
        List<Store.Task> candidates = new ArrayList<>();
        List<Store.Task> ready = new ArrayList<>();
        Instant cutoff = now.minus(LATE_WINDOW);
        Instant oldestOpen = now.atZone(Store.BEIJING).minusYears(1).toInstant();
        for (Store.Task task : store.tasks()) {
            Instant due = task.dueInstant();
            Instant opens = task.opensInstant();
            if (task.completed() || due == null || due.isBefore(cutoff) || sent.contains(task.reminderKey())
                    || (opens != null && opens.isBefore(oldestOpen))) continue;
            boolean upcoming = due.isAfter(now);
            if (upcoming) candidates.add(task);
            if (planned <= now.toEpochMilli() && !trigger(task, lead).isAfter(now)
                    && (upcoming || plannedTasks.contains(task.reminderKey())) && !plannedTasks.isEmpty()) {
                ready.add(task);
            }
        }
        if (!ready.isEmpty() && !sendDeadlines(context, sent, ready)) {
            cancel(context, DEADLINE);
            return;
        }
        if (!enabled(context, DEADLINE_CHANNEL)) {
            cancel(context, DEADLINE);
            return;
        }
        Instant earliest = null;
        Set<String> keys = new HashSet<>();
        for (Store.Task task : candidates) {
            String key = task.reminderKey();
            if (sent.contains(key)) continue;
            Instant when = trigger(task, lead);
            if (when.isBefore(now)) when = now;
            if (earliest == null || when.isBefore(earliest)) {
                earliest = when;
                keys.clear();
            }
            if (when.equals(earliest)) keys.add(key);
        }
        if (earliest == null) {
            clearDeadlines(context, state);
            return;
        }
        arm(context, DEADLINE, earliest);
        state.edit().remove(DEADLINE).putLong(DEADLINE + ".at", earliest.toEpochMilli())
                .putStringSet(DEADLINE_TASKS, keys).putInt(DEADLINE_LEAD, lead).apply();
    }

    private static Instant trigger(Store.Task task, int lead) {
        return task.dueInstant().minusSeconds(lead * 60L);
    }

    private static void clearDeadlines(Context context, SharedPreferences state) {
        cancel(context, DEADLINE);
        state.edit().remove(DEADLINE).remove(DEADLINE + ".at")
                .remove(DEADLINE_TASKS).remove(DEADLINE_LEAD).apply();
    }

    private static void arm(Context context, String action, Instant when) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        Intent intent = new Intent(context, Reminders.class).setAction(action);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        long wallTime = Math.max(when.toEpochMilli(), System.currentTimeMillis() + 1000);
        // Setting the same PendingIntent replaces its alarm, including after system cancellation.
        set(manager, AlarmManager.RTC_WAKEUP, pending, wallTime, canScheduleExact(context));
    }

    private static void cancel(Context context, String action) {
        Intent intent = new Intent(context, Reminders.class).setAction(action);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (pending != null) context.getSystemService(AlarmManager.class).cancel(pending);
    }

    private static SharedPreferences alarmState(Context context) {
        return context.getSharedPreferences("scheduled_reminders", Context.MODE_PRIVATE);
    }

    static Instant nextDaily(Store.Settings settings, Instant now, ZoneId zone) {
        LocalDate date = now.atZone(zone).toLocalDate();
        LocalTime time = LocalTime.of(settings.hour(), settings.minute());
        ZonedDateTime target = date.atTime(time).atZone(zone);
        if (!target.toInstant().isAfter(now)) target = date.plusDays(1).atTime(time).atZone(zone);
        return target.toInstant();
    }

    static boolean scheduleTest(Context context) {
        createChannels(context);
        if (!enabled(context, DEADLINE_CHANNEL)) return false;
        long when = SystemClock.elapsedRealtime() + 60_000;
        Intent intent = new Intent(context, Reminders.class).setAction(TEST).putExtra(TEST_WHEN, when);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);
        set(context.getSystemService(AlarmManager.class), AlarmManager.ELAPSED_REALTIME_WAKEUP,
                pending, when, canScheduleExact(context));
        return true;
    }

    private static void set(AlarmManager manager, int type, PendingIntent intent, long when, boolean exact) {
        if (exact) {
            try {
                manager.setExactAndAllowWhileIdle(type, when, intent);
                return;
            } catch (SecurityException ignored) {
                // The special access can be revoked between checking and scheduling.
            }
        }
        manager.setAndAllowWhileIdle(type, when, intent);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (TEST.equals(action)) {
            long when = intent.getLongExtra(TEST_WHEN, -1);
            if (when > 0 && when <= SystemClock.elapsedRealtime()) {
                createChannels(context);
                post(context, DEADLINE_CHANNEL, 3, "课业日程 · 试提醒",
                        "系统已成功投递这条试提醒。", List.of());
            }
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ScanJob.configure(context);
        }
        schedule(context);
    }

    private static boolean sendDaily(Context context, Store store, Instant now) {
        List<Store.Task> tasks = activeTasks(store, now);
        String text = tasks.isEmpty() ? "当前没有待办课业。" : "还有 " + tasks.size() + " 项待办课业。";
        return post(context, DAILY_CHANNEL, 1, "课业日程 · 每日汇总", text, tasks);
    }

    private static boolean sendDeadlines(Context context, SharedPreferences sent, List<Store.Task> ready) {
        ready.sort(Store.byDeadline());
        String text = ready.get(0).title();
        if (ready.size() > 1) text += " 等 " + ready.size() + " 项任务";
        if (!post(context, DEADLINE_CHANNEL, 2, "课业日程 · 截止提醒", text, ready)) return false;
        SharedPreferences.Editor edit = sent.edit();
        for (Store.Task task : ready) edit.putLong(task.reminderKey(), task.dueInstant().toEpochMilli());
        edit.apply();
        return true;
    }

    private static List<Store.Task> activeTasks(Store store, Instant now) {
        List<Store.Task> result = new ArrayList<>();
        for (Store.Task task : store.tasks()) {
            Instant due = task.dueInstant();
            Instant opens = task.opensInstant();
            if (!task.completed() && (due == null || due.isAfter(now))
                    && (opens == null || !opens.isBefore(now.atZone(Store.BEIJING).minusYears(1).toInstant()))) {
                result.add(task);
            }
        }
        result.sort(Store.byDeadline());
        return result;
    }

    private static SharedPreferences history(Context context) {
        return context.getSharedPreferences("reminded", Context.MODE_PRIVATE);
    }

    private static void prune(SharedPreferences prefs, Instant now) {
        long cutoff = now.minus(Duration.ofDays(14)).toEpochMilli();
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (var entry : prefs.getAll().entrySet()) {
            if (entry.getValue() instanceof Long due && due < cutoff) {
                editor.remove(entry.getKey());
                changed = true;
            }
        }
        if (changed) editor.apply();
    }

    private static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(DAILY_CHANNEL,
                "每日汇总", NotificationManager.IMPORTANCE_DEFAULT));
        manager.createNotificationChannel(new NotificationChannel(DEADLINE_CHANNEL,
                "截止提醒", NotificationManager.IMPORTANCE_HIGH));
    }

    static boolean channelEnabled(Context context, String channel) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel category = manager.getNotificationChannel(channel);
        return category != null && category.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static boolean enabled(Context context, String channel) {
        return context.getSystemService(NotificationManager.class).areNotificationsEnabled()
                && channelEnabled(context, channel);
    }

    private static boolean post(Context context, String channel, int id, String title, String text,
                                List<Store.Task> tasks) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!enabled(context, channel)) {
            return false;
        }
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent link = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notification = new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                .setContentIntent(link).setAutoCancel(true);
        if (!tasks.isEmpty()) {
            Notification.InboxStyle detail = new Notification.InboxStyle().setBigContentTitle(title);
            for (int i = 0; i < Math.min(tasks.size(), 5); i++) {
                Store.Task task = tasks.get(i);
                Instant due = task.dueInstant();
                String suffix = due == null ? "" : "  ·  " + DUE_DISPLAY.format(due);
                detail.addLine(task.title() + suffix);
            }
            if (tasks.size() > 5) detail.setSummaryText("共 " + tasks.size() + " 项");
            notification.setStyle(detail);
        }
        try {
            manager.notify(id, notification.build());
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }
}



