package com.homework.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class MoocScan {
    private static final String ORIGIN = "https://www.icourse163.org";
    private static final int PAGE_SIZE = 20;
    private static final DateTimeFormatter CHINA_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX").withZone(ZoneOffset.ofHours(8));

    interface Reader {
        Object read(MoocRpc.Endpoint endpoint, String... parameters) throws IOException, JSONException;
    }

    private MoocScan() {}

    static JSONObject collect(Reader reader, JSONArray previousTasks, long now) {
        Results output = new Results(previousTasks);
        Map<Long, Course> courses = new LinkedHashMap<>();
        try {
            readDirectory(reader, false, now, courses, output.issues);
            readDirectory(reader, true, now, courses, output.issues);
            Map<Long, JSONObject> terms = new HashMap<>();
            Map<Long, JSONArray> newExams = new HashMap<>();
            for (Course course : courses.values()) {
                readTerm(reader, course, course.term(), terms, newExams, output);
                if (course.source() > 0 && course.source() != course.term()) {
                    readTerm(reader, course, course.source(), terms, newExams, output);
                }
            }
        } catch (MoocRpc.AuthRequired error) {
            return ApiScan.failure("login_required", "MOOC 登录会话已失效，请重新验证账号");
        }
        String status = output.issues.length() == 0 ? "ok" : output.tasks.length() > 0 ? "partial" : "error";
        return ApiScan.result(status, output.tasks, output.issues);
    }

    private record Course(String name, String path, long term, long source) {
        String key() {
            return path + "|" + term;
        }

        String url() {
            return ORIGIN + path + "?tid=" + term;
        }
    }

    private static void readDirectory(Reader reader, boolean spoc, long now,
                                      Map<Long, Course> courses, JSONArray issues) throws MoocRpc.AuthRequired {
        int lastPage = 1;
        int received = 0;
        int total = 0;
        Set<Long> seen = new HashSet<>();
        try {
            for (int page = 1; page <= lastPage; page++) {
                JSONObject payload = object(reader.read(MoocRpc.Endpoint.DIRECTORY,
                        "type", "30", "p", Integer.toString(page), "psize", Integer.toString(PAGE_SIZE),
                        "courseType", spoc ? "2" : "1"));
                JSONObject pagination = payload.getJSONObject("pagination");
                int pageTotal = pagination.getInt("totlePageCount");
                int count = pagination.getInt("totleCount");
                if (pageTotal < 0 || count < 0 || pagination.getInt("pageIndex") != page
                        || (page > 1 && (pageTotal != lastPage || count != total))) {
                    throw new JSONException("invalid pagination");
                }
                lastPage = pageTotal;
                total = count;
                JSONArray entries = array(payload.get("result"));
                received += entries.length();
                for (int index = 0; index < entries.length(); index++) {
                    JSONObject entry = entries.getJSONObject(index);
                    JSONObject term = entry.getJSONObject("termPanel");
                    long termId = term.getLong("id");
                    if (termId <= 0 || !seen.add(termId)) throw new JSONException("duplicate term");
                    long start = term.optLong("startTime");
                    long end = term.optLong("endTime");
                    if ((start > 0 && now < start) || (end > 0 && now >= end)) continue;
                    long courseId = entry.getLong("id");
                    JSONObject school = entry.optJSONObject("schoolPanel");
                    if (school == null) school = term.getJSONObject("schoolPanel");
                    String schoolName = school.getString("shortName");
                    String name = label(entry, "name");
                    if (name.isEmpty() || courseId <= 0 || !schoolName.matches("[A-Za-z0-9_-]+")) {
                        throw new JSONException("invalid course");
                    }
                    String path = (spoc ? "/spoc/learn/" : "/learn/") + schoolName + "-" + courseId;
                    // Synchronous SPOC terms display their own work and the source term's work.
                    long source = spoc && term.optInt("mode") == 10 ? term.optLong("fromTermId") : 0;
                    courses.putIfAbsent(termId, new Course(name, path, termId, source));
                }
            }
            if (received != total) throw new JSONException("incomplete directory");
        } catch (MoocRpc.AuthRequired error) {
            throw error;
        } catch (IOException | JSONException error) {
            issues.put((spoc ? "SPOC" : "MOOC") + " · 课程目录接口未完整返回");
        }
    }

    private static void readTerm(Reader reader, Course course, long termId, Map<Long, JSONObject> terms,
                                 Map<Long, JSONArray> newExams, Results output) throws MoocRpc.AuthRequired {
        String scope = course.name() + (termId == course.term() ? "" : " · 源课程");
        try {
            JSONObject term = terms.get(termId);
            if (term == null) {
                JSONObject response = object(reader.read(MoocRpc.Endpoint.TERM, "termId", Long.toString(termId)));
                term = response.getJSONObject("mocTermDto");
                if (term.getLong("id") != termId) throw new JSONException("unexpected term");
                terms.put(termId, term);
            }
            JSONArray chapters = array(term.get("chapters"));
            for (int index = 0; index < chapters.length(); index++) {
                JSONObject chapter = chapters.getJSONObject(index);
                if (!chapter.optBoolean("published", true) || !chapter.optBoolean("visible", true)) continue;
                readAssessments(array(chapter.get("quizs")), course, termId, false, output);
                readAssessments(array(chapter.get("homeworks")), course, termId, true, output);
            }
            JSONArray exams = array(term.get("exams"));
            for (int index = 0; index < exams.length(); index++) {
                JSONObject exam = exams.getJSONObject(index);
                String target = course.url() + "#/learn/examlist";
                long objective = exam.optLong("objectTestId");
                long subjective = exam.optLong("subjectTestId");
                long examId = exam.getLong("id");
                if (termId == course.term() && objective > 0) {
                    target = course.url() + "#/learn/examObject?eid=" + examId + "&id=" + objective
                            + "&examType=" + exam.optInt("examType");
                } else if (termId == course.term() && subjective > 0) {
                    target = course.url() + "#/learn/examSubjective?eid=" + examId + "&id=" + subjective;
                }
                output.add(course, "exam", examId, label(exam, "name"), exam.optLong("releaseTime"),
                        exam.optLong("deadline"), taskStatus(exam), target, !target.endsWith("examlist"));
            }
        } catch (MoocRpc.AuthRequired error) {
            throw error;
        } catch (IOException | JSONException error) {
            output.issues.put(scope + " · 学期任务接口未完整返回");
        }

        try {
            JSONArray exams = newExams.get(termId);
            if (exams == null) {
                exams = array(reader.read(MoocRpc.Endpoint.NEW_EXAMS, "termId", Long.toString(termId)));
                newExams.put(termId, exams);
            }
            for (int index = 0; index < exams.length(); index++) {
                JSONObject exam = exams.getJSONObject(index);
                long id = positive(exam, "examId", "id");
                String title = label(exam, "examName");
                if (title.isEmpty()) title = label(exam, "name");
                output.add(course, "exam", id, title, positive(exam, "startTime", "releaseTime"),
                        positive(exam, "endTime", "deadline"), taskStatus(exam),
                        course.url() + "#/learn/examlist", false);
            }
        } catch (MoocRpc.AuthRequired error) {
            throw error;
        } catch (IOException | JSONException error) {
            output.issues.put(scope + " · 新版考试接口未完整返回");
        }
    }

    private static void readAssessments(JSONArray items, Course course, long termId, boolean assignment,
                                        Results output) throws JSONException {
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            if (!item.optBoolean("visible", true)) continue;
            JSONObject test = item.getJSONObject("test");
            long taskId = item.getLong("contentId");
            String title = label(item, "name");
            if (title.isEmpty()) title = label(test, "name");
            boolean direct = termId == course.term();
            String section = direct ? (assignment ? "/learn/hw?id=" : "/learn/quiz?id=") + taskId : "/learn/testlist";
            output.add(course, assignment ? "assignment" : "quiz", taskId, title,
                    test.optLong("releaseTime") > 0 ? test.optLong("releaseTime") : item.optLong("releaseTime"),
                    test.optLong("deadline"), taskStatus(test), course.url() + "#" + section, direct);
        }
    }

    private static long positive(JSONObject object, String first, String second) {
        long value = object.optLong(first);
        return value > 0 ? value : object.optLong(second);
    }

    private static String label(JSONObject object, String field) {
        return object.isNull(field) ? "" : object.optString(field).replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
    }

    private static String taskStatus(JSONObject task) {
        if (!task.isNull("userScore") && task.optDouble("userScore", -1) >= 0) return "已评分";
        return task.optInt("usedTryCount") > 0 ? "已提交" : "";
    }

    private static JSONArray array(Object value) throws JSONException {
        if (value == JSONObject.NULL) return new JSONArray();
        if (value instanceof JSONArray items) return items;
        throw new JSONException("expected array");
    }

    private static JSONObject object(Object value) throws JSONException {
        if (value instanceof JSONObject item) return item;
        throw new JSONException("expected object");
    }

    private static String time(long millis) {
        return millis > 0 ? CHINA_TIME.format(Instant.ofEpochMilli(millis)) : "";
    }

    private static final class Results {
        private final JSONArray issues = new JSONArray();
        private final JSONArray tasks = new JSONArray();
        private final Map<String, String> apiIds = new HashMap<>();
        private final Map<String, ArrayDeque<String>> oldIds = new HashMap<>();
        private final Set<String> emitted = new HashSet<>();

        Results(JSONArray previousTasks) {
            for (int index = 0; index < previousTasks.length(); index++) {
                JSONObject task = previousTasks.optJSONObject(index);
                if (task == null) continue;
                String id = task.optString("id");
                if (id.isEmpty()) continue;
                String source = task.optString("api_id");
                if (!source.isEmpty()) {
                    apiIds.put(source, id);
                } else {
                    String name = task.optString("course_id") + "|" + task.optString("kind") + "|" + task.optString("title");
                    oldIds.computeIfAbsent(name, ignored -> new ArrayDeque<>()).add(id);
                }
            }
        }

        void add(Course course, String kind, long externalId, String title, long opens, long due,
                 String status, String url, boolean direct) throws JSONException {
            if (externalId <= 0 || title.isEmpty()) throw new JSONException("invalid task");
            String source = course.key() + "|" + kind + "|rpc-" + externalId;
            if (!emitted.add(source)) return;
            String id = apiIds.get(source);
            if (id == null) {
                ArrayDeque<String> matching = oldIds.get(course.key() + "|" + kind + "|" + title);
                id = matching != null && !matching.isEmpty() ? matching.removeFirst() : source;
            }
            tasks.put(new JSONObject().put("id", id).put("api_id", source).put("course_id", course.key())
                    .put("course_name", course.name()).put("title", title).put("kind", kind)
                    .put("opens_at", time(opens)).put("due_at", time(due)).put("due_precision", due > 0 ? "exact" : "")
                    .put("status", status).put("open_url", url).put("open_scope", direct ? "task" : "section"));
        }
    }
}

