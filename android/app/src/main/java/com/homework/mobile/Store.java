package com.homework.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Store {
    static final String CHAOXING = "chaoxing";
    static final String MOOC = "icourse163";
    static final List<String> PLATFORMS = java.util.Arrays.asList(CHAOXING, MOOC);
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter STATUS_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(BEIJING);
    private static final String HIDDEN_COURSES = "hidden_courses";
    private static final String HIDDEN_TASKS = "hidden_tasks";
    private static final Object BLACKLIST_LOCK = new Object();
    private final SharedPreferences prefs;

    Store(Context context) {
        prefs = context.getSharedPreferences("agenda", Context.MODE_PRIVATE);
    }

    record Settings(boolean background, int interval, boolean daily, int hour, int minute,
                    boolean deadline, int lead) {
        Settings {
            interval = Math.max(15, Math.min(1440, interval));
            hour = Math.max(0, Math.min(23, hour));
            minute = Math.max(0, Math.min(59, minute));
            lead = Math.max(0, Math.min(10080, lead));
        }
    }

    record BlacklistEntry(String key, String name, String platform, boolean course, String courseName) {}

    record Task(String platform, String id, String courseId, String course, String title, String kind,
                String opens, String due, String precision, String status, String url, String scope) {
        Instant opensInstant() {
            return parseTime(opens, false);
        }

        Instant dueInstant() {
            return parseTime(due, true);
        }

        boolean completed() {
            String value = status.replaceAll("\\s+", "");
            if (value.matches(".*(?:未提交|待提交|未完成|未作答|未参加|未通过|未答题|未交|未考试|缺考).*")) {
                return false;
            }
            return value.matches(".*(?:已提交|已完成|已作答|已答题|已参加|已交|待批阅|已批阅|已批改|已评分|已通过|已考试|查看成绩|查看答卷|查看结果).*");
        }

        String courseKey() {
            return courseId.isEmpty() ? legacyCourseKey() : platform + ":id:" + courseId;
        }

        private String legacyCourseKey() {
            return platform + ":name:" + course;
        }

        private String taskKey() {
            return platform + ":" + id;
        }

        String reminderKey() {
            return platform + ":" + id + ":" + due;
        }
    }

    Settings settings() {
        return new Settings(prefs.getBoolean("background", false), prefs.getInt("interval", 15),
                prefs.getBoolean("daily", false), prefs.getInt("hour", 20), prefs.getInt("minute", 0),
                prefs.getBoolean("deadline", false), prefs.getInt("lead", 60));
    }

    void saveSettings(Settings settings) {
        prefs.edit().putBoolean("background", settings.background()).putInt("interval", settings.interval())
                .putBoolean("daily", settings.daily()).putInt("hour", settings.hour())
                .putInt("minute", settings.minute()).putBoolean("deadline", settings.deadline())
                .putInt("lead", settings.lead()).apply();
    }

    boolean connected(String platform) {
        return prefs.getBoolean("login_attempted_" + platform, false);
    }

    String accountProfile(String platform) {
        return prefs.getString("account_profile_" + platform, null);
    }

    void applyAccount(String platform, String profile, boolean resetTasks) {
        SharedPreferences.Editor edit = prefs.edit().putString("account_profile_" + platform, profile)
                .putBoolean("login_attempted_" + platform, profile != null)
                .remove("status_" + platform).remove("detail_" + platform)
                .remove("attempt_" + platform).remove("success_" + platform);
        if (resetTasks) edit.remove("tasks_" + platform);
        edit.apply();
    }

    boolean hasConnectedPlatforms() {
        return PLATFORMS.stream().anyMatch(this::connected);
    }

    static String platformName(String platform) {
        return CHAOXING.equals(platform) ? "学习通" : "中国大学 MOOC / SPOC";
    }

    String platformStatus(String platform) {
        if (!connected(platform)) return "待填写账号";
        String status = prefs.getString("status_" + platform, "");
        long attempted = prefs.getLong("attempt_" + platform, 0);
        String detail = prefs.getString("detail_" + platform, "");
        int size = tasks(platform).size();
        String label = switch (status) {
            case "ok" -> "扫描完成 · " + size + " 项任务";
            case "partial" -> "部分更新 · " + size + " 项任务";
            case "login_required" -> "会话已失效，请重新登录";
            case "error" -> "扫描失败 · 已保留 " + size + " 项任务";
            default -> "等待验证与扫描";
        };
        if (attempted != 0) label += " · " + STATUS_TIME.format(Instant.ofEpochMilli(attempted));
        return detail.trim().isEmpty() ? label : label + "\n" + detail;
    }

    void applyScan(String platform, JSONObject result) {
        String status = result.optString("status", "error");
        JSONArray fresh = result.optJSONArray("tasks");
        if ("ok".equals(status) && fresh == null) status = "error";
        Map<String, JSONObject> rows = new LinkedHashMap<>();
        Set<String> refreshedLists = new HashSet<>();
        JSONArray listUpdates = result.optJSONArray("refreshed_task_lists");
        if ("partial".equals(status) && fresh != null && listUpdates != null) {
            for (int i = 0; i < listUpdates.length(); i++) refreshedLists.add(listUpdates.optString(i));
        }
        if (!"ok".equals(status)) putRows(rows, storedRows(platform), refreshedLists);
        if (fresh != null && ("ok".equals(status) || "partial".equals(status))) putRows(rows, fresh, Set.of());
        JSONArray issues = result.optJSONArray("issues");
        List<String> summary = new ArrayList<>();
        if (issues != null) {
            for (int i = 0; i < Math.min(3, issues.length()); i++) {
                String issue = issues.optString(i);
                if (!issue.trim().isEmpty()) summary.add(issue);
            }
            if (issues.length() > 3) summary.add("另有 " + (issues.length() - 3) + " 处");
        }
        long attemptedAt = System.currentTimeMillis();
        long lastSuccess = lastSuccess(platform);
        if ("ok".equals(status)) lastSuccess = attemptedAt;
        prefs.edit().putString("tasks_" + platform, new JSONArray(rows.values()).toString())
                .putString("status_" + platform, status)
                .putLong("attempt_" + platform, attemptedAt)
                .putLong("success_" + platform, lastSuccess)
                .putString("detail_" + platform, String.join("；", summary)).apply();
    }

    long lastAttempt(String platform) {
        return prefs.getLong("attempt_" + platform, 0L);
    }

    long lastSuccess(String platform) {
        long success = prefs.getLong("success_" + platform, 0L);
        if (success > 0L) return success;
        if (!"ok".equals(prefs.getString("status_" + platform, ""))) return 0L;
        return Math.max(0L, prefs.getLong("attempt_" + platform, 0L));
    }

    static boolean stale(long lastSuccess, long now, long intervalMillis) {
        return lastSuccess <= 0L || lastSuccess > now || now - lastSuccess >= intervalMillis;
    }

    static String taskListKey(String courseId, String kind) {
        return courseId + "|" + kind;
    }

    private static void putRows(Map<String, JSONObject> records, JSONArray data, Set<String> refreshedLists) {
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            if (refreshedLists.contains(taskListKey(row.optString("course_id"), row.optString("kind")))) continue;
            String id = optional(row, "id");
            if (!id.trim().isEmpty() && !optional(row, "title").trim().isEmpty()) records.put(id, row);
        }
    }

    JSONArray storedRows(String platform) {
        try {
            return new JSONArray(prefs.getString("tasks_" + platform, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    void hideTask(Task task) {
        addToBlacklist(HIDDEN_TASKS, task.taskKey(), task.title(), task.course());
    }

    void hideCourse(Task task) {
        addToBlacklist(HIDDEN_COURSES, task.courseKey(), task.course(), "");
    }

    private void addToBlacklist(String list, String key, String name, String courseName) {
        synchronized (BLACKLIST_LOCK) {
            Set<String> hidden = new HashSet<>(prefs.getStringSet(list, Collections.emptySet()));
            if (hidden.add(key)) {
                prefs.edit().putStringSet(list, hidden).putString(labelKey(list, key), name)
                        .putString(courseLabelKey(list, key), courseName).apply();
            }
        }
    }

    void restore(BlacklistEntry entry) {
        String list = entry.course() ? HIDDEN_COURSES : HIDDEN_TASKS;
        String key = entry.key();
        synchronized (BLACKLIST_LOCK) {
            Set<String> hidden = new HashSet<>(prefs.getStringSet(list, Collections.emptySet()));
            if (hidden.remove(key)) {
                prefs.edit().putStringSet(list, hidden).remove(labelKey(list, key))
                        .remove(courseLabelKey(list, key)).apply();
            }
        }
    }

    List<BlacklistEntry> blacklist() {
        Map<String, Task> cachedCourses = new LinkedHashMap<>();
        Map<String, Task> cachedTasks = new LinkedHashMap<>();
        for (String platform : PLATFORMS) {
            for (Task task : cachedTasks(platform)) {
                cachedCourses.putIfAbsent(task.courseKey(), task);
                cachedCourses.putIfAbsent(task.legacyCourseKey(), task);
                cachedTasks.put(task.taskKey(), task);
            }
        }
        List<BlacklistEntry> entries = new ArrayList<>();
        addBlacklistEntries(entries, HIDDEN_COURSES, cachedCourses, true);
        addBlacklistEntries(entries, HIDDEN_TASKS, cachedTasks, false);
        entries.sort(Comparator.comparing((BlacklistEntry entry) -> !entry.course())
                .thenComparing(BlacklistEntry::name).thenComparing(BlacklistEntry::key));
        return entries;
    }

    private void addBlacklistEntries(List<BlacklistEntry> entries, String list,
                                     Map<String, Task> cached, boolean course) {
        for (String key : prefs.getStringSet(list, Collections.emptySet())) {
            int separator = key.indexOf(':');
            if (separator < 0) continue;
            String platform = key.substring(0, separator);
            String name = prefs.getString(labelKey(list, key), "");
            String courseName = prefs.getString(courseLabelKey(list, key), "");
            Task task = cached.get(key);
            if (task != null) {
                name = course ? task.course() : task.title();
                if (!course) courseName = task.course();
            }
            if (name.isEmpty()) name = fallbackLabel(key.substring(separator + 1), course);
            entries.add(new BlacklistEntry(key, name, platform, course, courseName));
        }
    }

    private static String fallbackLabel(String identity, boolean course) {
        if (!course) return "任务 " + identity;
        if (identity.startsWith("name:")) return identity.substring(5);
        return "课程 " + (identity.startsWith("id:") ? identity.substring(3) : identity);
    }

    private static String labelKey(String list, String key) {
        return list + "_label_" + key;
    }

    private static String courseLabelKey(String list, String key) {
        return list + "_course_label_" + key;
    }

    List<Task> tasks() {
        List<Task> visible = new ArrayList<>();
        for (String platform : PLATFORMS) visible.addAll(tasks(platform));
        return visible;
    }

    private List<Task> tasks(String platform) {
        Set<String> hiddenCourses = prefs.getStringSet(HIDDEN_COURSES, Collections.emptySet());
        Set<String> hiddenTasks = prefs.getStringSet(HIDDEN_TASKS, Collections.emptySet());
        List<Task> visible = cachedTasks(platform);
        visible.removeIf(task -> hiddenCourses.contains(task.courseKey())
                || hiddenCourses.contains(task.legacyCourseKey()) || hiddenTasks.contains(task.taskKey()));
        return visible;
    }

    private List<Task> cachedTasks(String platform) {
        List<Task> tasks = new ArrayList<>();
        JSONArray rows = storedRows(platform);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            tasks.add(new Task(platform, optional(row, "id"), optional(row, "course_id"),
                    optional(row, "course_name"), optional(row, "title"), optional(row, "kind"),
                    optional(row, "opens_at"), optional(row, "due_at"), optional(row, "due_precision"),
                    optional(row, "status"), optional(row, "open_url"), optional(row, "open_scope")));
        }
        return tasks;
    }

    private static String optional(JSONObject row, String name) {
        return row.isNull(name) ? "" : row.optString(name, "");
    }

    static Comparator<Task> byDeadline() {
        return Comparator.comparing((Task task) ->
                        task.dueInstant() == null ? task.opensInstant() : task.dueInstant(),
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Task::title);
    }

    static Instant parseTime(String value, boolean endOfDay) {
        if (value.trim().isEmpty()) return null;
        try {
            if (value.length() == 10) {
                LocalDate date = LocalDate.parse(value);
                return endOfDay ? date.plusDays(1).atStartOfDay(BEIJING).toInstant().minusSeconds(1)
                        : date.atStartOfDay(BEIJING).toInstant();
            }
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

