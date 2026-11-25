package com.homework.mobile;

import android.test.InstrumentationTestCase;

import org.json.JSONObject;

import java.io.IOException;

@SuppressWarnings("deprecation")
public final class ChaoxingApiLoginTest extends InstrumentationTestCase {
    public void testServerRequestedChallengeStopsBeforePasswordSubmission() throws Exception {
        ChaoxingApiLogin.verifyBootstrap("""
                <input id="needVcode" value=""/>
                <input id="doubleFactorLogin" value="0"/>
                """);
        try {
            ChaoxingApiLogin.verifyBootstrap("""
                    <input id="needVcode" value="1"/>
                    <input id="doubleFactorLogin" value="0"/>
                    """);
            fail("Required verification must block native password submission");
        } catch (ChaoxingApiLogin.LoginFailure expected) {
            assertTrue(expected.interactive);
        }
    }

    public void testAcceptedPasswordStillRequiresRequestedSecondFactor() throws Exception {
        JSONObject reply = new JSONObject().put("status", true).put("containTwoFactorLogin", true)
                .put("url", "https://i.mooc.chaoxing.com/space/index");
        try {
            ChaoxingApiLogin.acceptedReturn(reply);
            fail("Second factor must be completed before a session can be published");
        } catch (ChaoxingApiLogin.LoginFailure expected) {
            assertTrue(expected.interactive);
        }
    }

    public void testReturnUrlOnlyUsesApprovedHttpsHosts() throws Exception {
        JSONObject reply = new JSONObject().put("status", true)
                .put("url", "http%3A%2F%2Fi.mooc.chaoxing.com%2Fspace%2Findex");
        assertEquals("https://i.mooc.chaoxing.com/space/index", ChaoxingApiLogin.acceptedReturn(reply));
        for (String unsafe : new String[]{"https://passport2.chaoxing.com.example.test/",
                "https://passport2.chaoxing.com@evil.example.test/", "https://passport2.chaoxing.com:444/"}) {
            reply.put("url", unsafe);
            try {
                ChaoxingApiLogin.acceptedReturn(reply);
                fail("Unapproved login return");
            } catch (IOException expected) {
                assertEquals("学习通登录跳转地址无效", expected.getMessage());
            }
        }
    }
}
