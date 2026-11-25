package com.homework.mobile;

import android.test.InstrumentationTestCase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("deprecation")
public final class MoocScanTest extends InstrumentationTestCase {
    private static final long NOW = 1795507200000L;

    public void testReadsPagedCoursesAndIncludesSynchronousSpocSource() throws Exception {
        Map<Long, Integer> termReads = new HashMap<>();
        JSONArray cached = new JSONArray("""
                [{"id":"existing-id","course_id":"/learn/TEST-10|100","kind":"quiz","title":"测验一"}]
                """);
        JSONObject result = MoocScan.collect((endpoint, args) -> {
            long termId = Long.parseLong(args[1]);
            return switch (endpoint) {
                case DIRECTORY -> {
                    assertEquals("30", args[1]);
                    assertEquals("courseType", args[6]);
                    if ("2".equals(args[7])) {
                        yield directory(1, 1, course(20, 200, 10, 100, 0));
                    }
                    if ("1".equals(args[3])) yield directory(1, 2, course(10, 100, 0, 0, 0));
                    assertEquals("2", args[3]);
                    yield directory(2, 2, course(30, 300, 0, 0, NOW - 1));
                }
                case TERM -> {
                    termReads.merge(termId, 1, Integer::sum);
                    if (termId == 100) yield new JSONObject("""
                            {"mocTermDto":{"id":100,"chapters":[{"published":true,
                             "quizs":[{"contentId":11,"name":"测验一","test":{"releaseTime":1795003200000,"deadline":1798545600000}}],
                             "homeworks":null}],"exams":[]}}
                            """);
                    assertEquals(200, termId);
                    yield new JSONObject("""
                            {"mocTermDto":{"id":200,"chapters":[{"published":true,"quizs":[],
                             "homeworks":[{"contentId":12,"name":"本班作业","test":{"deadline":1798545600000,"usedTryCount":1}}]}],
                             "exams":[{"id":13,"name":"期末考试","deadline":1798545600000,"objectTestId":14}]}}
                            """);
                }
                case NEW_EXAMS -> termId == 200 ? JSONObject.NULL : new JSONArray();
            };
        }, cached, NOW);
        assertEquals(result.toString(), "ok", result.getString("status"));
        assertEquals(2, termReads.size());
        assertEquals(Integer.valueOf(1), termReads.get(100L));
        JSONArray tasks = result.getJSONArray("tasks");
        assertEquals(4, tasks.length());

        JSONObject quiz = tasks.getJSONObject(0);
        assertEquals("existing-id", quiz.getString("id"));
        assertEquals("2026-12-29T20:00:00+08:00", quiz.getString("due_at"));
        assertEquals("2026-11-18T20:00:00+08:00", quiz.getString("opens_at"));
        assertTrue(quiz.getString("open_url").endsWith("#/learn/quiz?id=11"));

        JSONObject work = tasks.getJSONObject(1);
        assertEquals("已提交", work.getString("status"));
        assertEquals("assignment", work.getString("kind"));
        assertTrue(work.getString("open_url").endsWith("#/learn/hw?id=12"));

        JSONObject exam = tasks.getJSONObject(2);
        assertEquals("exam", exam.getString("kind"));
        assertTrue(exam.getString("open_url").contains("examObject?eid=13&id=14"));

        JSONObject source = tasks.getJSONObject(3);
        assertEquals("测验一", source.getString("title"));
        assertEquals("/spoc/learn/TEST-20|200", source.getString("course_id"));
        assertEquals("section", source.getString("open_scope"));
    }

    public void testFailedNewExamApiKeepsTasksAndReportsPartial() throws Exception {
        JSONObject result = MoocScan.collect((endpoint, args) -> switch (endpoint) {
            case DIRECTORY -> "1".equals(args[7]) ? directory(1, 1, course(10, 100, 0, 0, 0))
                    : new JSONObject("{\"pagination\":{\"totlePageCount\":1,\"totleCount\":0,\"pageIndex\":1},\"result\":null}");
            case TERM -> new JSONObject("""
                    {"mocTermDto":{"id":100,"chapters":[],"exams":[{"id":8,"name":"已发布考试","deadline":1798545600000}]}}
                    """);
            case NEW_EXAMS -> throw new IOException("unavailable");
        }, new JSONArray(), NOW);
        assertEquals("partial", result.getString("status"));
        assertEquals(1, result.getJSONArray("tasks").length());
        assertTrue(result.getJSONArray("issues").getString(0).contains("新版考试接口"));
    }

    public void testExpiredSessionIsNotAnEmptySuccessfulScan() throws Exception {
        JSONObject result = MoocScan.collect((endpoint, args) -> {
            throw new MoocRpc.AuthRequired();
        }, new JSONArray(), NOW);
        assertEquals("login_required", result.getString("status"));
    }

    private static JSONObject directory(int page, int total, JSONObject course) throws org.json.JSONException {
        return new JSONObject().put("pagination", new JSONObject().put("totlePageCount", total)
                .put("totleCount", total).put("pageIndex", page)).put("result", new JSONArray().put(course));
    }

    private static JSONObject course(long courseId, long termId, int mode, long source, long end)
            throws org.json.JSONException {
        return new JSONObject().put("id", courseId).put("name", "测试课 " + courseId)
                .put("schoolPanel", new JSONObject().put("shortName", "TEST"))
                .put("termPanel", new JSONObject().put("id", termId).put("mode", mode)
                        .put("fromTermId", source).put("endTime", end));
    }
}

