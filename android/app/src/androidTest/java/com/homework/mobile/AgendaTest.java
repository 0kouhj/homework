package com.homework.mobile;

import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.test.InstrumentationTestCase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("deprecation")
public final class AgendaTest extends InstrumentationTestCase {
    public void testCachedTasksSurviveIncompleteScans() throws Exception {
        Store store = isolatedStore();
        store.applyScan(Store.CHAOXING, result("ok", "old", "机械实验"));
        store.applyScan(Store.MOOC, result("ok", "mooc", "MOOC 测验"));
        store.applyScan(Store.CHAOXING, result("partial", "new", "数字逻辑作业"));
        store.applyScan(Store.CHAOXING, result("login_required", "", ""));
        assertEquals(3, store.tasks().size());
        assertTrue(store.tasks().stream().anyMatch(task -> task.title().equals("机械实验")));
        store.applyScan(Store.CHAOXING, result("ok", "", ""));
        assertEquals(1, store.tasks().size());
        assertEquals(Store.MOOC, store.tasks().get(0).platform());
    }

    public void testBlacklistsSurviveScansAndKeepOtherClasses() throws Exception {
        Context context = isolatedContext();
        Store store = new Store(context);
        JSONObject first = scannedTask("shared", "class-1", "同名课程", "单独隐藏的任务");
        JSONObject peer = scannedTask("peer", "class-1", "同名课程", "仍显示的任务");
        JSONObject second = scannedTask("second", "class-2", "同名课程", "整门课程都隐藏");
        store.applyScan(Store.CHAOXING, scanRows(first, peer, second));
        store.applyScan(Store.MOOC, scanRows(first));

        store.hideTask(store.tasks().get(0));
        store.hideCourse(store.tasks().stream().filter(item -> item.id().equals("second")).findFirst().get());

        Store reopened = new Store(context);
        reopened.applyScan(Store.CHAOXING, scanRows(first, peer,
                scannedTask("new", "class-2", "课程已改名", "下轮扫描的新任务")));
        assertEquals(2, reopened.tasks().size());
        assertEquals("peer", reopened.tasks().get(0).id());
        assertEquals(Store.MOOC, reopened.tasks().get(1).platform());
        assertEquals("shared", reopened.tasks().get(1).id());
    }

    public void testBlacklistedLegacyCourseStaysHiddenWhenIdAppears() throws Exception {
        Store store = isolatedStore();
        store.applyScan(Store.CHAOXING, scanRows(scannedTask("old", "", "旧快照课程", "旧任务")));
        store.hideCourse(store.tasks().get(0));
        store.applyScan(Store.CHAOXING, scanRows(scannedTask("new", "class-1", "旧快照课程", "新任务")));
        assertTrue(store.tasks().isEmpty());
    }

    public void testRestoreCourseKeepsIndividuallyHiddenTask() throws Exception {
        Context context = isolatedContext();
        Store store = new Store(context);
        store.applyScan(Store.CHAOXING, scanRows(
                scannedTask("task-a", "class-1", "测试课程", "单独隐藏的任务"),
                scannedTask("task-b", "class-1", "测试课程", "随课程隐藏的任务")));
        List<Store.Task> before = store.tasks();
        store.hideTask(before.get(0));
        store.hideCourse(before.get(1));
        assertTrue(store.tasks().isEmpty());

        Store reopened = new Store(context);
        Store.BlacklistEntry course = reopened.blacklist().stream()
                .filter(Store.BlacklistEntry::course).findFirst().get();
        assertEquals("测试课程", course.name());
        reopened.restore(course);
        assertEquals(1, reopened.tasks().size());
        assertEquals("task-b", reopened.tasks().get(0).id());
        assertEquals(1, reopened.blacklist().size());

        Store.BlacklistEntry task = reopened.blacklist().get(0);
        assertFalse(task.course());
        assertEquals("测试课程", task.courseName());
        reopened.restore(task);
        assertEquals(2, reopened.tasks().size());
        assertTrue(new Store(context).blacklist().isEmpty());
    }

    public void testBlacklistListsOldEntriesAndKeepsLabelsWithoutCachedRows() throws Exception {
        Context context = isolatedContext();
        Store store = new Store(context);
        store.applyScan(Store.CHAOXING, scanRows(scannedTask(
                "old", "class-1", "早期版本课程", "已拉黑的旧任务")));
        context.getSharedPreferences("agenda", Context.MODE_PRIVATE).edit()
                .putStringSet("hidden_tasks", Collections.singleton("chaoxing:old")).apply();

        Store.BlacklistEntry legacy = store.blacklist().get(0);
        assertEquals("已拉黑的旧任务", legacy.name());
        store.restore(legacy);
        Store.Task task = store.tasks().get(0);
        store.hideCourse(task);
        store.hideTask(task);
        store.applyScan(Store.CHAOXING, scanRows());

        List<Store.BlacklistEntry> entries = new Store(context).blacklist();
        assertTrue(entries.stream().anyMatch(entry -> entry.course() && entry.name().equals("早期版本课程")));
        assertTrue(entries.stream().anyMatch(entry -> !entry.course() && entry.name().equals("已拉黑的旧任务")));
        for (Store.BlacklistEntry entry : entries) store.restore(entry);
        store.applyScan(Store.CHAOXING, scanRows(scannedTask(
                "old", "class-1", "早期版本课程", "已拉黑的旧任务")));
        assertEquals(1, store.tasks().size());
    }

    private static JSONObject scannedTask(String id, String courseId, String course, String title)
            throws Exception {
        return new JSONObject().put("id", id).put("course_id", courseId)
                .put("course_name", course).put("title", title);
    }

    private static JSONObject scanRows(JSONObject... rows) throws Exception {
        return new JSONObject().put("status", "ok").put("tasks", new JSONArray(Arrays.asList(rows)));
    }

    public void testPasswordVaultRoundTripAndSeparatePlatforms() {
        Context isolated = new ContextWrapper(isolatedContext()) {
            @Override public Context getApplicationContext() {
                return this;
            }
        };
        AccountVault vault = new AccountVault(isolated);
        AccountVault.Account first = new AccountVault.Account("synthetic-user@example.test", "synthetic-password");
        AccountVault.Account second = new AccountVault.Account("synthetic-phone", "synthetic-other-password");
        try {
            vault.save(Store.CHAOXING, first);
            vault.save(Store.MOOC, second);
            assertEquals(first, vault.read(Store.CHAOXING));
            String record = isolated.getSharedPreferences("account_vault_chaoxing", Context.MODE_PRIVATE)
                    .getString("credential", "");
            assertFalse(record.contains(first.username()));
            assertFalse(record.contains(first.password()));
            assertFalse(first.toString().contains(first.password()));
            vault.clear(Store.CHAOXING);
            assertNull(vault.read(Store.CHAOXING));
            assertEquals(second, vault.read(Store.MOOC));
        } finally {
            vault.clear(Store.CHAOXING);
            vault.clear(Store.MOOC);
        }
    }

    public void testTaskFilterAndDeadlineOrder() {
        Instant now = Instant.parse("2026-12-01T01:00:00Z");
        Store.Task late = timedTask("较晚截止", "", "2026-12-10", "");
        Store.Task early = timedTask("较早截止", "", "2026-12-01T12:00:00+08:00", "");
        Store.Task expired = timedTask("已截止", "", "2026-11-30", "");
        Store.Task completed = timedTask("已完成", "", "2026-12-02", "已完成");
        Store.Task unknown = timedTask("没有截止时间", "2026-11-01", "", "");
        Store.Task waitingReview = timedTask("等待教师批阅", "", "", "待批阅");
        Store.Task noDates = timedTask("没有日期", "", "", "");

        List<Store.Task> tasks = new ArrayList<>(
                Arrays.asList(late, noDates, unknown, completed, early, expired, waitingReview));
        tasks.sort(TaskFilter.ORDER);
        assertEquals(Arrays.asList(expired, early, completed, late, unknown, noDates, waitingReview), tasks);
        assertEquals(Arrays.asList(early, late), filtered(tasks, TaskFilter.UPCOMING, now));
        assertEquals(Arrays.asList(unknown, noDates), filtered(tasks, TaskFilter.UNKNOWN, now));
        assertEquals(Arrays.asList(completed, waitingReview), filtered(tasks, TaskFilter.COMPLETED, now));
        assertTrue(waitingReview.completed());
        assertEquals(tasks, filtered(tasks, TaskFilter.ALL, now));
        assertFalse(TaskFilter.isUpcoming(early.completed(), now, now));
    }

    private static Store.Task timedTask(String title, String opens, String due, String status) {
        return new Store.Task(Store.CHAOXING, title, "class-1", "模拟课程", title, "assignment",
                opens, due, "", status, "", "");
    }

    private static List<Store.Task> filtered(List<Store.Task> tasks,
                                                       TaskFilter filter, Instant now) {
        List<Store.Task> result = new ArrayList<>();
        for (Store.Task task : tasks) {
            Instant due = task.dueInstant();
            boolean completed = task.completed();
            if (filter.includes(TaskFilter.isUpcoming(completed, due, now), completed, due)) result.add(task);
        }
        return result;
    }

    public void testDeadlineAndDailyClock() {
        assertEquals(Instant.parse("2026-12-01T15:59:59Z"), Store.parseTime("2026-12-01", true));
        Store.Settings settings = new Store.Settings(true, 5, true, 20, 15, true, 90);
        assertEquals(15, settings.interval());
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        assertEquals(Instant.parse("2026-12-01T12:15:00Z"),
                Reminders.nextDaily(settings, Instant.parse("2026-12-01T10:15:00Z"), zone));
        assertEquals(Instant.parse("2026-12-02T12:15:00Z"),
                Reminders.nextDaily(settings, Instant.parse("2026-12-01T12:15:00Z"), zone));
        assertFalse(PlatformWeb.allowed(Store.CHAOXING, "https://chaoxing.com.example.org"));
        assertFalse(PlatformWeb.allowed(Store.MOOC, "https://www.icourse163.org@other.example"));
    }

    public void testPersistentJobUpdatesAndCanBeCancelled() {
        Context context = getInstrumentation().getTargetContext();
        Store store = new Store(context);
        Store.Settings previous = store.settings();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        try {
            store.saveSettings(new Store.Settings(true, 22, false, 20, 0, false, 60));
            ScanJob.configure(context);
            JobInfo job = scheduler.getPendingJob(ScanJob.PERIODIC);
            assertNotNull(job);
            assertTrue(job.isPeriodic());
            assertTrue(job.isPersisted());
            assertEquals(22 * 60_000L, job.getIntervalMillis());

            store.saveSettings(new Store.Settings(true, 35, false, 20, 0, false, 60));
            ScanJob.configure(context);
            assertEquals(35 * 60_000L, scheduler.getPendingJob(ScanJob.PERIODIC).getIntervalMillis());

            store.saveSettings(new Store.Settings(false, 35, false, 20, 0, false, 60));
            ScanJob.configure(context);
            assertNull(scheduler.getPendingJob(ScanJob.PERIODIC));
        } finally {
            store.saveSettings(previous);
            ScanJob.configure(context);
        }
    }

    public void testCatchUpUsesLegacySuccessAndThrottlesRepeatedFailures() throws Exception {
        Context context = isolatedContext();
        Store store = new Store(context);
        store.saveSettings(new Store.Settings(true, 15, false, 20, 0, false, 60));
        store.applyAccount(Store.CHAOXING, "synthetic-profile", true);
        long legacySuccess = System.currentTimeMillis() - 90 * 60_000L;
        context.getSharedPreferences("agenda", Context.MODE_PRIVATE).edit()
                .putString("status_chaoxing", "ok").putLong("attempt_chaoxing", legacySuccess).apply();
        assertEquals(legacySuccess, store.lastSuccess(Store.CHAOXING));
        assertFalse(ScanJob.catchUpDue(store, legacySuccess + 15 * 60_000L - 1));
        assertTrue(ScanJob.catchUpDue(store, legacySuccess + 15 * 60_000L));

        store.applyScan(Store.CHAOXING, result("error", "", ""));
        assertEquals(legacySuccess, store.lastSuccess(Store.CHAOXING));
        long failedAt = store.lastAttempt(Store.CHAOXING);
        assertFalse(ScanJob.catchUpDue(store, failedAt + 5 * 60_000L - 1));
        assertTrue(ScanJob.catchUpDue(store, failedAt + 5 * 60_000L));
        assertTrue(ScanJob.catchUpDue(store, legacySuccess - 1));
        store.applyAccount(Store.CHAOXING, null, false);
        assertEquals(0, store.lastSuccess(Store.CHAOXING));
        assertFalse(ScanJob.catchUpDue(store, failedAt + 5 * 60_000L));
    }

    public void testNotificationDeliveryAndDeadlineDeduplication() throws Exception {
        Context context = isolatedContext();
        Store store = new Store(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        assertTrue("The instrumentation install must grant notifications", manager.areNotificationsEnabled());
        ZoneId zone = Store.BEIJING;
        Instant slot = LocalDate.now(zone).plusDays(1).atTime(12, 0).atZone(zone).toInstant();
        store.applyScan(Store.CHAOXING, scanRows(
                scannedTask("planned", "class", "模拟课程", "先前安排的模拟作业").put("due_at", slot.toString()),
                scannedTask("old", "class", "模拟课程", "启用前已截止").put("due_at", slot.minusSeconds(1800).toString())));
        store.saveSettings(new Store.Settings(false, 15, true, 12, 0, true, 0));
        try {
            Reminders.schedule(context, slot.minusSeconds(60), zone);
            assertFalse(hasNotification(manager, "课业日程 · 每日汇总"));
            assertFalse(hasNotification(manager, "课业日程 · 截止提醒"));
            Reminders.schedule(context, slot.plusSeconds(60), zone);
            assertTrue(hasNotification(manager, "课业日程 · 每日汇总"));
            assertTrue(hasNotification(manager, "课业日程 · 截止提醒"));
            assertEquals(1, context.getSharedPreferences("reminded", Context.MODE_PRIVATE)
                    .getAll().keySet().stream().filter(key -> !key.startsWith("daily_on_")).count());
            manager.cancel(1);
            manager.cancel(2);
            Reminders.schedule(context, slot.plusSeconds(120), zone);
            assertFalse("The same daily slot must not post twice",
                    hasNotification(manager, "课业日程 · 每日汇总"));
            assertFalse(hasNotification(manager, "课业日程 · 截止提醒"));
            store.saveSettings(new Store.Settings(false, 15, true, 12, 5, true, 0));
            Reminders.schedule(context, slot.plusSeconds(180), zone);
            Reminders.schedule(context, slot.plusSeconds(360), zone);
            assertFalse("Changing the time must not reissue today's summary",
                    hasNotification(manager, "课业日程 · 每日汇总"));
        } finally {
            manager.cancel(1);
            manager.cancel(2);
            Reminders.schedule(getInstrumentation().getTargetContext());
        }
    }

    public void testExpiredAndNeverPlannedRemindersAreNotBackfilled() throws Exception {
        Context context = isolatedContext();
        Store store = new Store(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ZoneId zone = Store.BEIJING;
        Instant slot = LocalDate.now(zone).plusDays(1).atTime(12, 0).atZone(zone).toInstant();
        store.applyScan(Store.CHAOXING, scanRows(
                scannedTask("past", "class", "模拟课程", "已截止").put("due_at", slot.toString())));
        store.saveSettings(new Store.Settings(false, 15, true, 12, 0, true, 0));
        try {
            Reminders.schedule(context, slot.plusSeconds(30), zone);
            assertFalse(hasNotification(manager, "课业日程 · 每日汇总"));
            assertFalse(hasNotification(manager, "课业日程 · 截止提醒"));
            Reminders.schedule(context, slot.minusSeconds(60), zone);
            Reminders.schedule(context, slot.plusSeconds(121 * 60), zone);
            assertFalse(hasNotification(manager, "课业日程 · 每日汇总"));
            assertFalse(hasNotification(manager, "课业日程 · 截止提醒"));
        } finally {
            manager.cancel(1);
            manager.cancel(2);
            Reminders.schedule(getInstrumentation().getTargetContext());
        }
    }

    private boolean hasNotification(NotificationManager manager, String title) {
        getInstrumentation().waitForIdleSync();
        for (var item : manager.getActiveNotifications()) {
            if (title.equals(item.getNotification().extras.getString("android.title"))) return true;
        }
        return false;
    }

    private Store isolatedStore() {
        return new Store(isolatedContext());
    }

    private Context isolatedContext() {
        return new ContextWrapper(getInstrumentation().getTargetContext()) {
            private final String prefix = "instrumented_" + System.nanoTime() + "_";

            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
    }

    private static JSONObject result(String status, String id, String title) throws Exception {
        JSONArray tasks = new JSONArray();
        if (!id.isEmpty()) {
            tasks.put(new JSONObject().put("id", id).put("title", title).put("course_name", "模拟课程"));
        }
        return new JSONObject().put("status", status).put("tasks", tasks);
    }
}
