package com.homework.mobile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;

final class MoocRpc {
    private static final String ORIGIN = "https://www.icourse163.org";

    enum Endpoint {
        DIRECTORY("learnerCourseRpcBean.getMyLearnedCoursePanelList"),
        TERM("courseBean.getLastLearnedMocTermDto"),
        NEW_EXAMS("courseBean.getNewExamList");

        final String path;

        Endpoint(String name) {
            path = "/web/j/" + name + ".rpc";
        }
    }

    static final class AuthRequired extends IOException {}

    private final JsonHttp http;

    MoocRpc(JsonHttp http) {
        this.http = http;
    }

    Object read(Endpoint endpoint, String... parameters) throws IOException, JSONException {
        String base = ORIGIN + endpoint.path;
        String header = http.cookies(base);
        String csrf = JsonHttp.cookie(header, "NTESSTUDYSI");
        if (csrf.isEmpty() || JsonHttp.cookie(header, "STUDY_SESS").isEmpty()) throw new AuthRequired();

        JSONObject response;
        try {
            response = http.postForm(base + "?csrfKey=" + encode(csrf), header, ORIGIN, form(parameters));
        } catch (JsonHttp.HttpFailure error) {
            if (error.status == 401) throw new AuthRequired();
            throw error;
        }
        int code = response.getInt("code");
        if (code == -1002) throw new AuthRequired();
        if (code != 0) throw new IOException("MOOC 接口状态 " + code);
        return response.get("result");
    }

    private static String form(String[] parameters) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < parameters.length; i += 2) {
            if (i > 0) body.append('&');
            body.append(encode(parameters[i])).append('=').append(encode(parameters[i + 1]));
        }
        return body.toString();
    }

    private static String encode(String text) throws IOException {
        return URLEncoder.encode(text, "UTF-8");
    }
}

