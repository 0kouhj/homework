package com.homework.mobile;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import android.test.InstrumentationTestCase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class ChaoxingScanTest extends InstrumentationTestCase {
    public void testScansEveryStudentClassAndAllPages() throws Exception {
        JSONArray previous = new JSONArray("""
                [{"course_id":"10:20","kind":"assignment","title":"作业 0","id":"10:20|assignment|1000|0",
                  "open_url":"https://stat2-ans.chaoxing.com/work?workId=1000","open_scope":"task"}]
                """);
        List<String> requests = new ArrayList<>();
        JSONObject result = ChaoxingScan.collect(url -> {
            Uri uri = Uri.parse(url);
            if ("/mycourse/backclazzdata".equals(uri.getPath())) return directory();
            assertEquals("stat2-ans.chaoxing.com", uri.getHost());
            String clazz = uri.getQueryParameter("clazzid");
            String cpi = "20".equals(clazz) ? "50" : "60";
            assertEquals(cpi, uri.getQueryParameter("cpi"));
            assertEquals(cpi, uri.getQueryParameter("personId"));
            assertNull(uri.getQueryParameter("userid"));
            String path = uri.getPath();
            boolean work = "/work-stastics/stu-works".equals(path);
            if (!work) assertEquals("/exam-stastics/stu-exams", path);
            int page = Integer.parseInt(uri.getQueryParameter("page"));
            requests.add(clazz + ":" + (work ? "work" : "exam") + ":" + page);
            if (work && "20".equals(clazz)) {
                JSONArray workRows = new JSONArray();
                if (page == 1) {
                    for (int i = 0; i < 20; i++) workRows.put(row(20, 101 + i, "作业 " + i)
                            .put("workId", 1000 + i));
                } else {
                    workRows.put(row(20, 121, "下一页作业"));
                }
                return listPage(page, 2, workRows);
            }
            if (!work && "40".equals(clazz)) {
                return listPage(page, 1, new JSONArray().put(row(40, 201, "考试…")
                        .put("titleAll", "历史课程考试").put("statusStr", "已完成")));
            }
            return listPage(page, 0, new JSONArray());
        }, "100", previous);

        assertEquals(List.of("20:work:1", "20:work:2", "20:exam:1", "40:work:1", "40:exam:1"), requests);
        assertEquals("ok", result.getString("status"));
        JSONArray tasks = result.getJSONArray("tasks");
        assertEquals(22, tasks.length());
        JSONObject work = tasks.getJSONObject(0);
        assertEquals("10:20|assignment|1000|0", work.getString("id"));
        assertEquals("task", work.getString("open_scope"));
        assertEquals("2026-12-29T20:00:00+08:00", work.getString("due_at"));
        JSONObject exam = tasks.getJSONObject(21);
        assertEquals("exam", exam.getString("kind"));
        assertEquals("历史课程考试", exam.getString("title"));
        assertEquals("已完成", exam.getString("status"));
        assertEquals("section", exam.getString("open_scope"));
        assertTrue(exam.getString("open_url").contains("/exam-stastics/stu-index"));
    }

    public void testDeniedListsReturnErrorAndKeepCachedTasks() throws Exception {
        Store store = isolatedStore();
        JSONArray previous = new JSONArray("""
                [{"id":"old-work","course_id":"10:20","kind":"assignment","title":"旧作业"}]
                """);
        store.applyScan(Store.CHAOXING, ApiScan.result("ok", previous, new JSONArray()));
        int[] attempts = {0};
        JSONObject result = ChaoxingScan.collect(url -> {
            if ("/mycourse/backclazzdata".equals(Uri.parse(url).getPath())) return directory();
            attempts[0]++;
            throw new JsonHttp.HttpFailure(403);
        }, "100", previous);
        assertEquals(4, attempts[0]);
        assertEquals("error", result.getString("status"));
        assertTrue(result.getJSONArray("issues").toString().contains("HTTP 403"));
        store.applyScan(Store.CHAOXING, result);
        assertEquals("old-work", store.storedRows(Store.CHAOXING).getJSONObject(0).getString("id"));
    }

    public void testRepeatedWorkDoesNotInheritAmbiguousLegacyId() throws Exception {
        JSONArray previous = new JSONArray("""
                [{"course_id":"10:20","kind":"assignment","title":"重复作业","id":"10:20|assignment|1000|0"}]
                """);
        JSONObject result = ChaoxingScan.collect(url -> {
            Uri uri = Uri.parse(url);
            if ("/mycourse/backclazzdata".equals(uri.getPath())) return directory();
            if ("20".equals(uri.getQueryParameter("clazzid")) && uri.getPath().endsWith("stu-works")) {
                return listPage(1, 1, new JSONArray()
                        .put(row(20, 101, "重复作业").put("workId", 1000))
                        .put(row(20, 102, "重复作业").put("workId", 1000)));
            }
            return listPage(1, 0, new JSONArray());
        }, "100", previous);
        JSONArray tasks = result.getJSONArray("tasks");
        assertEquals("10:20|assignment|wr:101|0", tasks.getJSONObject(0).getString("id"));
        assertEquals("10:20|assignment|wr:102|0", tasks.getJSONObject(1).getString("id"));
    }

    public void testBadSecondPageKeepsThatListAndRefreshesOtherLists() throws Exception {
        Store store = isolatedStore();
        JSONArray previous = new JSONArray("""
                [{"id":"old-work","course_id":"10:20","kind":"assignment","title":"旧作业"},
                 {"id":"old-exam","course_id":"30:40","kind":"exam","title":"已被移除的考试"}]
                """);
        store.applyScan(Store.CHAOXING, ApiScan.result("ok", previous, new JSONArray()));
        JSONObject result = ChaoxingScan.collect(url -> {
            Uri uri = Uri.parse(url);
            if ("/mycourse/backclazzdata".equals(uri.getPath())) return directory();
            if ("20".equals(uri.getQueryParameter("clazzid")) && uri.getPath().endsWith("stu-works")) {
                JSONArray rows = new JSONArray();
                for (int i = 0; i < 20; i++) rows.put(row(20, i + 1, "作业 " + i));
                return listPage(1, 2, rows);
            }
            return listPage(1, 0, new JSONArray());
        }, "100", previous);
        assertEquals("partial", result.getString("status"));
        store.applyScan(Store.CHAOXING, result);
        JSONArray retained = store.storedRows(Store.CHAOXING);
        assertEquals(1, retained.length());
        assertEquals("old-work", retained.getJSONObject(0).getString("id"));
    }

    private Store isolatedStore() {
        return new Store(new ContextWrapper(getInstrumentation().getTargetContext()) {
            private final String prefix = "cxscan_" + System.nanoTime() + "_";

            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        });
    }

    private static JSONObject directory() throws JSONException {
        return new JSONObject("""
                {"result":1,"hasMore":false,"channelList":[
                    {"content":{"folderName":"课程文件夹"}},
                    {"content":{"id":20,"cpi":50,"state":0,"roletype":3,"isstart":true,"isFiled":0,
                                "course":{"data":[{"id":10,"name":"当前课程"}]}}},
                    {"content":{"id":40,"cpi":60,"state":1,"roletype":3,"isstart":false,"isFiled":1,
                                "course":{"data":[{"id":30,"name":"历史课程"}]}}},
                    {"content":{"id":80,"cpi":90,"state":0,"roletype":1,
                                "course":{"data":[{"id":70,"name":"非学生课程"}]}}}
                ]}
                """);
    }

    private static JSONObject listPage(int page, int totalPages, JSONArray rows) throws JSONException {
        return new JSONObject().put("page", page).put("totalPage", totalPages).put("data", rows);
    }

    private static JSONObject row(int clazz, int relation, String title) throws JSONException {
        return new JSONObject().put("clazzId", clazz).put("wrId", relation).put("title", title)
                .put("statusStr", "未提交").put("endTimeLong", 1798545600000L);
    }
}
