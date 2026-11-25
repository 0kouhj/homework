package com.homework.mobile;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ChaoxingScan {
    private static final String DIRECTORY = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1";
    private static final String STATISTICS = "https://stat2-ans.chaoxing.com";
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 1000;
    private static final DateTimeFormatter CHINA_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX").withZone(ZoneOffset.ofHours(8));

    interface Reader {
        JSONObject get(String url) throws IOException, JSONException;
    }

    private record Course(long id, long clazzId, long cpi, String name) {
        String key() {
            return id + ":" + clazzId;
        }
    }

    private enum Section {
        WORK("assignment", "作业", "/work-stastics/stu-works", "/work-stastics/stu-index"),
        EXAM("exam", "考试", "/exam-stastics/stu-exams", "/exam-stastics/stu-index");

        final String kind;
        final String label;
        final String listPath;
        final String pagePath;

        Section(String kind, String label, String listPath, String pagePath) {
            this.kind = kind;
            this.label = label;
            this.listPath = listPath;
            this.pagePath = pagePath;
        }
    }

    private record Detail(long relation, long workId, String title, String status, long opens, long due) {}

    private ChaoxingScan() {}

    static JSONObject collect(JsonHttp http, JSONArray previous) {
        String uid = JsonHttp.cookie(http.cookies(DIRECTORY), "_uid");
        return collect(http::get, uid, previous);
    }

    static JSONObject collect(Reader read, String uid, JSONArray previous) {
        if (!uid.matches("\\d+")) {
            return ApiScan.failure("login_required", "学习通登录会话缺少账号凭据");
        }
        Map<String, Course> courses;
        try {
            courses = readDirectory(read);
        } catch (JsonHttp.HttpFailure error) {
            return ApiScan.failure(error.status == 401 ? "login_required" : "error",
                    "学习通课程目录接口返回 HTTP " + error.status);
        } catch (IOException | JSONException error) {
            return ApiScan.failure("error", "学习通课程目录接口未返回完整 JSON");
        }

        Map<String, List<JSONObject>> previousLists = new HashMap<>();
        for (int i = 0; i < previous.length(); i++) {
            JSONObject row = previous.optJSONObject(i);
            if (row == null || text(row, "id").isEmpty()) continue;
            String key = Store.taskListKey(row.optString("course_id"), row.optString("kind"));
            previousLists.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }

        JSONArray tasks = new JSONArray();
        JSONArray refreshed = new JSONArray();
        List<String> failures = new ArrayList<>();
        int completedCourses = 0;
        for (Course course : courses.values()) {
            if (course.cpi() <= 0) {
                failures.add(course.name() + "缺少学生课程标识");
                continue;
            }
            int before = refreshed.length();
            for (Section section : Section.values()) {
                String key = Store.taskListKey(course.key(), section.kind);
                try {
                    List<Detail> details = readDetails(read, course, section);
                    for (JSONObject row : taskRows(course, section, details, previousLists.getOrDefault(key, List.of()))) {
                        tasks.put(row);
                    }
                    refreshed.put(key);
                } catch (JsonHttp.HttpFailure error) {
                    if (error.status == 401) {
                        return ApiScan.failure("login_required", "学习通任务明细接口要求重新登录");
                    }
                    failures.add(course.name() + "的" + section.label + "接口返回 HTTP " + error.status);
                } catch (IOException | JSONException error) {
                    failures.add(course.name() + "的" + section.label + "列表未完整返回");
                }
            }
            if (refreshed.length() - before == Section.values().length) completedCourses++;
        }

        JSONArray details = new JSONArray().put("已同步 " + completedCourses + "/" + courses.size() + " 门课的作业和考试");
        for (String failure : failures) details.put(failure);
        String status = failures.isEmpty() ? "ok" : refreshed.length() == 0 ? "error" : "partial";
        JSONObject result = ApiScan.result(status, tasks, details);
        try {
            return result.put("refreshed_task_lists", refreshed);
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Map<String, Course> readDirectory(Reader read) throws IOException, JSONException {
        JSONObject directory = read.get(DIRECTORY);
        if (directory.getInt("result") != 1 || directory.getBoolean("hasMore")) {
            throw new JSONException("incomplete directory");
        }
        Map<String, Course> courses = new LinkedHashMap<>();
        JSONArray items = directory.getJSONArray("channelList");
        for (int i = 0; i < items.length(); i++) {
            JSONObject clazz = items.getJSONObject(i).optJSONObject("content");
            if (clazz == null || clazz.optInt("roletype") != 3) continue;
            long clazzId = clazz.getLong("id");
            JSONArray entries = clazz.getJSONObject("course").getJSONArray("data");
            for (int j = 0; j < entries.length(); j++) {
                JSONObject row = entries.getJSONObject(j);
                long courseId = row.getLong("id");
                String title = text(row, "name");
                if (courseId <= 0 || clazzId <= 0 || title.isEmpty()) throw new JSONException("incomplete course");
                Course course = new Course(courseId, clazzId, clazz.optLong("cpi"), title);
                courses.putIfAbsent(course.key(), course);
            }
        }
        return courses;
    }

    private static List<Detail> readDetails(Reader read, Course course, Section section)
            throws IOException, JSONException {
        List<Detail> details = new ArrayList<>();
        Set<Long> seenRelations = new HashSet<>();
        int expectedPages = 0;
        for (int page = 1; ; page++) {
            String url = address(section.listPath, course)
                    .appendQueryParameter("personId", Long.toString(course.cpi()))
                    .appendQueryParameter("page", Integer.toString(page))
                    .appendQueryParameter("pageSize", Integer.toString(PAGE_SIZE)).toString();
            JSONObject response = read.get(url);
            int total = response.getInt("totalPage");
            if (page == 1) expectedPages = total;
            JSONArray rows = response.getJSONArray("data");
            if (response.getInt("page") != page || total != expectedPages || total < 0 || total > MAX_PAGES
                    || rows.length() > PAGE_SIZE || (page < total && rows.length() != PAGE_SIZE)
                    || (total == 0 && rows.length() != 0) || (page > 1 && rows.length() == 0)) {
                throw new JSONException("incomplete page");
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                long relation = row.getLong("wrId");
                String title = text(row, "titleAll");
                if (title.isEmpty()) title = text(row, "title");
                if (relation <= 0 || title.isEmpty() || row.getLong("clazzId") != course.clazzId()
                        || !seenRelations.add(relation)) {
                    throw new JSONException("incomplete or repeated task");
                }
                details.add(new Detail(relation, row.optLong("workId"), title, text(row, "statusStr"),
                        row.optLong("beginTimeLong"), row.optLong("endTimeLong")));
            }
            if (page >= total) return details;
        }
    }

    private static List<JSONObject> taskRows(Course course, Section section, List<Detail> details,
                                             List<JSONObject> previous) throws JSONException {
        Map<Long, JSONObject> byRelation = new HashMap<>();
        Map<String, JSONObject> legacyById = new HashMap<>();
        Map<String, List<JSONObject>> legacyByTitle = new HashMap<>();
        for (JSONObject old : previous) {
            long relation = old.optLong("relation_id");
            if (relation > 0) {
                byRelation.put(relation, old);
            } else {
                legacyById.put(old.optString("id"), old);
                legacyByTitle.computeIfAbsent(text(old, "title"), ignored -> new ArrayList<>()).add(old);
            }
        }
        Map<String, Integer> titleCounts = new HashMap<>();
        Map<Long, Integer> workIdCounts = new HashMap<>();
        for (Detail detail : details) {
            titleCounts.merge(detail.title(), 1, Integer::sum);
            workIdCounts.merge(detail.workId(), 1, Integer::sum);
        }

        List<JSONObject> result = new ArrayList<>(details.size());
        Set<String> usedIds = new HashSet<>();
        String prefix = Store.taskListKey(course.key(), section.kind) + "|";
        String sectionUrl = address(section.pagePath, course).toString();
        for (Detail detail : details) {
            JSONObject old = byRelation.get(detail.relation());
            if (old == null && detail.workId() > 0 && workIdCounts.get(detail.workId()) == 1) {
                JSONObject candidate = legacyById.get(prefix + detail.workId() + "|0");
                if (candidate != null && detail.title().equals(text(candidate, "title"))) old = candidate;
            }
            if (old == null && titleCounts.get(detail.title()) == 1) {
                List<JSONObject> candidates = legacyByTitle.getOrDefault(detail.title(), List.of());
                if (candidates.size() == 1) old = candidates.get(0);
            }
            if (old != null && !usedIds.add(old.optString("id"))) old = null;

            String id = old == null ? prefix + "wr:" + detail.relation() + "|0" : old.getString("id");
            String priorUrl = old == null ? "" : old.optString("open_url");
            boolean direct = old != null && "task".equals(old.optString("open_scope"))
                    && PlatformWeb.allowed(Store.CHAOXING, priorUrl);
            result.add(new JSONObject().put("id", id).put("relation_id", detail.relation())
                    .put("course_id", course.key()).put("course_name", course.name()).put("kind", section.kind)
                    .put("title", detail.title()).put("status", detail.status())
                    .put("opens_at", time(detail.opens())).put("due_at", time(detail.due()))
                    .put("due_precision", detail.due() > 0 ? "exact" : "")
                    .put("open_url", direct ? priorUrl : sectionUrl).put("open_scope", direct ? "task" : "section"));
        }
        return result;
    }

    private static Uri.Builder address(String path, Course course) {
        return Uri.parse(STATISTICS + path).buildUpon()
                .appendQueryParameter("courseid", Long.toString(course.id()))
                .appendQueryParameter("clazzid", Long.toString(course.clazzId()))
                .appendQueryParameter("cpi", Long.toString(course.cpi())).appendQueryParameter("ut", "s");
    }

    private static String text(JSONObject object, String field) {
        return object.isNull(field) ? "" : object.optString(field, "").replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
    }

    private static String time(long millis) {
        return millis > 0 ? CHINA_TIME.format(Instant.ofEpochMilli(millis)) : "";
    }
}
