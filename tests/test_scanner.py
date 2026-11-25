import json
import unittest
from contextlib import nullcontext
from datetime import datetime, timezone
from tempfile import TemporaryDirectory
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, call, patch
from urllib.parse import parse_qs, urlsplit

from homework_scan import __main__ as scanner, auth_status, browser, reauth
from homework_scan.chaoxing import detail_html, task_url
from homework_scan.dates import parse_times, remaining_time
from homework_scan.gui import Dashboard
from homework_scan.icourse163 import legacy_links, legacy_task_url
from homework_scan.model import Course, LoginRequired, Scan, Task
from homework_scan.store import read_snapshot, update_snapshot


class TimeTests(unittest.TestCase):
    def test_answer_window_without_year_uses_china_date_of_response(self):
        observed = datetime(2026, 11, 22, 15, 16, tzinfo=timezone.utc)
        text = "作答时间:10-26 11:34至11-11 11:35"
        self.assertEqual(
            parse_times(text, observed),
            ("2026-10-26T11:34:00+08:00", "2026-11-11T11:35:00+08:00", "exact"),
        )
        self.assertEqual(parse_times(text), (None, None, None))
        new_year = datetime(2025, 12, 31, 16, 5, tzinfo=timezone.utc)
        self.assertEqual(
            parse_times("作答时间：2025-12-19 11:12至01-12 23:59", new_year),
            ("2025-12-19T11:12:00+08:00", "2026-01-12T23:59:00+08:00", "exact"),
        )

    def test_answer_window_with_year_omitted_after_new_year(self):
        self.assertEqual(
            parse_times("作答时间:2025-12-19 11:12至01-12 23:59"),
            ("2025-12-19T11:12:00+08:00", "2026-01-12T23:59:00+08:00", "exact"),
        )

    def test_submission_time_is_not_exam_deadline(self):
        self.assertEqual(parse_times("领取时间：2026-11-21 14:49\n提交时间：2026-11-21 15:00"),
                         (None, None, None))

    def test_exam_window(self):
        self.assertEqual(
            parse_times("2026-11-23 09:00 至 2026-11-23 11:00"),
            ("2026-11-23T09:00:00+08:00", "2026-11-23T11:00:00+08:00", "exact"),
        )

    def test_countdown_uses_response_time(self):
        observed = datetime(2026, 11, 22, 7, 49, 12, tzinfo=timezone.utc)
        self.assertEqual(
            parse_times("剩余164小时56分钟", observed),
            (None, "2026-11-29T12:46:00+08:00", "estimated"),
        )

    def test_publication_is_not_due(self):
        self.assertEqual(parse_times("成绩公布：2026/12/01 18:00"), (None, None, None))
        self.assertEqual(parse_times("剩余2小时"), (None, None, None))
        self.assertEqual(parse_times("截止时间：2026年12月1日"), (None, "2026-12-01", "date"))

    def test_remaining_uses_current_time_and_preserves_unknown_precision(self):
        as_of = datetime(2026, 11, 22, 16, 40, tzinfo=timezone.utc)
        future = remaining_time("2026-11-23T01:40:00+08:00", as_of)
        self.assertEqual(future["seconds"], 3600)
        self.assertEqual(remaining_time("2026-11-23", as_of)["state"], "today")
        self.assertIsNone(remaining_time("2026-11-23", as_of)["seconds"])
        self.assertEqual(remaining_time("2026-11-22", as_of)["state"], "elapsed")
        self.assertEqual(remaining_time(None, as_of)["state"], "unknown")


class SnapshotTests(unittest.TestCase):
    def test_sort_partial_retain_and_full_replace(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "tasks.json"
            alpha = Course("chaoxing", "one", "课程甲")
            beta = Course("icourse163", "two", "课程乙")
            late = Task(alpha, "assignment", "1", "任务一", due_at="2026-12-30")
            early = Task(alpha, "assignment", "2", "任务二", due_at="2026-11-30")
            other = Task(beta, "exam", "1", "期末考试", due_at="2026-12-15")
            snapshot = read_snapshot(path)
            update_snapshot(snapshot, "chaoxing", "ok", Scan(1, [late, early]), "start", path)
            update_snapshot(snapshot, "icourse163", "ok", Scan(1, [other]), "start", path)
            update_snapshot(snapshot, "chaoxing", "partial", Scan(1, [late]), "start", path)
            saved = read_snapshot(path)
            self.assertEqual(saved["platforms"]["chaoxing"]["cached_task_count"], 1)
            self.assertEqual(
                [task["title"] for task in saved["courses"][0]["tasks"]], ["任务二", "任务一"]
            )
            update_snapshot(snapshot, "chaoxing", "login_required", Scan(), "start", path)
            self.assertEqual(snapshot["platforms"]["chaoxing"]["cached_task_count"], 2)
            update_snapshot(snapshot, "chaoxing", "ok", Scan(1, [late]), "start", path)
            saved = read_snapshot(path)
            self.assertEqual([course["course_name"] for course in saved["courses"]], ["课程乙", "课程甲"])


class TaskLinkTests(unittest.TestCase):
    def test_deadline_lookup_can_read_open_assignment_html_with_get(self):
        url = "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?workId=1"
        answer = "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/dowork"
        task = Task(Course("chaoxing", "course", "课程"), "assignment", "1", "作业",
                    status="未交", open_url=url)
        redirect = Mock(status=302, headers={"location": answer})
        document = Mock(ok=True, headers={"date": "Sun, 22 Nov 2026 15:16:44 GMT"})
        document.text.return_value = "<p>作答时间:10-26 11:34至11-11 11:35</p>"
        request = Mock()
        request.get.side_effect = [redirect, document]
        html, observed = detail_html(request, task)
        self.assertEqual(html, document.text.return_value)
        self.assertEqual(observed, datetime(2026, 11, 22, 15, 16, 44, tzinfo=timezone.utc))
        self.assertEqual([call.args[0] for call in request.get.call_args_list], [url, answer])
        request.post.assert_not_called()

    def test_deadline_lookup_does_not_follow_submission_redirect(self):
        url = "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?workId=1"
        task = Task(Course("chaoxing", "course", "课程"), "assignment", "1", "作业",
                    status="未交", open_url=url)
        response = Mock(status=302, headers={"location": "/mooc-ans/mooc2/work/submit"})
        request = Mock()
        request.get.return_value = response
        self.assertIsNone(detail_html(request, task))
        request.get.assert_called_once_with(url, max_redirects=0, timeout=5000)

    def test_chaoxing_exam_notes_respect_platform_entry_flag(self):
        section = "https://mooc1.chaoxing.com/exam-ans/mooc2/exam/exam-list"
        row = {"examContext": ["11", "22", "33"]}
        row["examAction"] = "viewExamAnswer('44', '55')"
        review = urlsplit(task_url(row, section, "exam"))
        self.assertEqual(review.path, "/exam-ans/exam/test/look")
        self.assertEqual(parse_qs(review.query)["examAnswerId"], ["55"])
        row["examAction"] = "goTest('11','44','55',0,'66',false,'enc',0)"
        instructions = urlsplit(task_url(row, section, "exam"))
        self.assertEqual(instructions.path, "/exam-ans/exam/test/examcode/examnotes")
        self.assertEqual(parse_qs(instructions.query)["examId"], ["44"])
        row["examAction"] = "goTest('11','44','55',0,'66',false,'enc',1)"
        self.assertIsNone(task_url(row, section, "exam"))

    def test_spoc_assignment_uses_matching_chapter_in_borrowed_term(self):
        section = urlsplit("https://www.icourse163.org/spoc/learn/EXAMPLE-1?tid=1#/learn/testlist")
        local = {"id": 1, "chapters": []}
        borrowed = {"id": 2, "chapters": [
            {"name": "第一章", "homeworks": [{"name": "作业", "contentId": 101}]},
            {"name": "第二章", "homeworks": [{"name": "作业", "contentId": 102}]},
        ]}
        responses = [
            SimpleNamespace(json=lambda term=term: {"result": {"mocTermDto": term}})
            for term in (local, borrowed)
        ]
        links = legacy_links(responses, section)
        address = legacy_task_url(links, {"chapter": " 第二章 "}, "assignment", "作业")
        self.assertEqual(address, "https://www.icourse163.org/spoc/learn/EXAMPLE-1?tid=1#/learn/hw?id=102")
        self.assertIsNone(legacy_task_url(links, {"chapter": "其他章"}, "assignment", "作业"))

    def test_gui_activates_saved_task_url(self):
        address = "https://www.icourse163.org/learn/EXAMPLE-1?tid=1#/learn/quiz?id=101"
        task = {"platform": "icourse163", "open_scope": "task", "open_url": address}
        dashboard = SimpleNamespace(tree=SimpleNamespace(focus=lambda: "row"), rows={"row": task})
        with patch("homework_scan.gui.webbrowser.open_new_tab", return_value=True) as open_tab:
            Dashboard.activate_row(dashboard, SimpleNamespace(keysym="Return"))
        open_tab.assert_called_once_with(address)


class SessionTests(unittest.TestCase):
    def test_password_status_belongs_to_the_saved_account(self):
        platform = "chaoxing"
        old_account = ("current-account", "current-password")
        new_account = ("updated-account", "updated-password")
        with (
            TemporaryDirectory() as directory,
            patch.object(browser, "STATE", Path(directory)),
            patch.object(reauth, "read_credentials", return_value=old_account),
            patch.object(reauth, "write_credentials") as write,
            patch.object(reauth, "delete_credentials") as delete,
        ):
            reauth.record_checked_account(platform, new_account, True)
            self.assertIsNone(auth_status.get_password_status(platform))
            reauth.record_checked_account(platform, old_account, True)
            reauth.record_checked_account(platform, new_account, False)
            self.assertIs(auth_status.get_password_status(platform), True)

            self.assertIsNone(reauth.change_credentials(platform, new_account))
            write.assert_called_once_with(platform, *new_account)
            self.assertIsNone(auth_status.get_password_status(platform))
            self.assertIsNone(reauth.change_credentials(platform, new_account, verified=True))
            self.assertIs(auth_status.get_password_status(platform), True)
            state = json.loads(auth_status.password_status_path(platform).read_text(encoding="utf-8"))
            self.assertEqual(state, {"verified": True})

            self.assertIsNone(reauth.change_credentials(platform, None))
            delete.assert_called_once_with(platform)
            self.assertIsNone(auth_status.get_password_status(platform))

    def test_verify_uses_entered_password_and_closes_fresh_context(self):
        driver = Mock()
        context = Mock()
        with (
            patch.object(browser, "launch", return_value=nullcontext(driver)) as launch,
            patch.object(browser, "new_context", return_value=context) as new_context,
            patch.object(reauth, "submit_password", side_effect=[True, False]) as submit,
            patch.object(reauth, "change_credentials") as change,
        ):
            self.assertIsNone(reauth.check_password_login("chaoxing", "msedge", " entered ", " secret "))
            self.assertTrue(reauth.check_password_login("icourse163", "chrome", "entered", "secret"))
        self.assertEqual(launch.call_args_list, [call(channel="msedge"), call(channel="chrome")])
        self.assertEqual(new_context.call_args_list, [call(driver), call(driver)])
        self.assertEqual(submit.call_args_list, [
            call(context, "chaoxing", "entered", " secret "),
            call(context, "icourse163", "entered", "secret"),
        ])
        self.assertEqual(context.close.call_count, 2)
        change.assert_not_called()

    def test_expired_scan_session_clears_the_success_status_of_that_account(self):
        platform = "chaoxing"
        account = ("user", "password")
        with (
            TemporaryDirectory() as directory,
            patch.object(browser, "STATE", Path(directory)),
            patch.object(scanner, "read_credentials", return_value=account),
            patch.object(reauth, "read_credentials", return_value=account),
            patch.object(scanner, "submit_password", return_value=True),
            patch.dict(scanner.ADAPTERS, {platform: Mock(side_effect=LoginRequired)}),
        ):
            self.assertEqual(scanner.scan_with_password(Mock(), platform)[0], "login_required")
            self.assertIs(auth_status.get_password_status(platform), False)

    def test_verify_rejects_empty_input_without_opening_browser(self):
        with patch.object(browser, "launch") as launch:
            self.assertTrue(reauth.check_password_login("chaoxing", "msedge", "  ", "secret"))
            self.assertTrue(reauth.check_password_login("chaoxing", "msedge", "entered", ""))
        launch.assert_not_called()

    def test_every_scan_logs_in_and_scans_in_the_same_fresh_context(self):
        driver = Mock()
        first, second = Mock(), Mock()
        driver.new_context.side_effect = [first, second]
        adapter = Mock(return_value=Scan())
        submit = Mock(return_value=True)
        sequence = Mock()
        sequence.attach_mock(submit, "submit")
        sequence.attach_mock(adapter, "scan")
        with (
            TemporaryDirectory() as directory,
            patch.object(browser, "STATE", Path(directory)),
            patch.object(scanner, "read_credentials", return_value=("user", "password")) as read,
            patch.object(scanner, "submit_password", submit),
            patch.dict(scanner.ADAPTERS, chaoxing=adapter),
        ):
            for _ in range(2):
                self.assertEqual(scanner.scan_with_password(lambda: driver, "chaoxing")[0], "ok")
                self.assertIs(auth_status.get_password_status("chaoxing"), True)

        fresh_context = call(locale="zh-CN", timezone_id="Asia/Shanghai", accept_downloads=False)
        self.assertEqual(driver.new_context.call_args_list, [fresh_context, fresh_context])
        self.assertEqual(read.call_args_list, [call("chaoxing"), call("chaoxing")])
        self.assertEqual(sequence.mock_calls, [
            call.submit(first, "chaoxing", "user", "password"), call.scan(first),
            call.submit(second, "chaoxing", "user", "password"), call.scan(second),
        ])
        first.close.assert_called_once_with()
        second.close.assert_called_once_with()

    def test_missing_or_failed_password_skips_scan_and_later_round_can_retry(self):
        driver, context = Mock(), Mock()
        driver.new_context.return_value = context
        get_browser = Mock(return_value=driver)
        adapter = Mock(return_value=Scan())
        credentials = ("user", "password")
        with (
            TemporaryDirectory() as directory,
            patch.object(browser, "STATE", Path(directory)),
            patch.object(scanner, "read_credentials", side_effect=[None, credentials, credentials]),
            patch.object(scanner, "submit_password", side_effect=[False, True]) as submit,
            patch.dict(scanner.ADAPTERS, icourse163=adapter),
        ):
            status, result = scanner.scan_with_password(get_browser, "icourse163")
            self.assertEqual(status, "login_required")
            self.assertIn("「账号」", " ".join(result.issues))
            get_browser.assert_not_called()
            self.assertEqual(scanner.scan_with_password(get_browser, "icourse163")[0], "login_required")
            self.assertIs(auth_status.get_password_status("icourse163"), False)
            submit.assert_called_once()
            adapter.assert_not_called()
            context.close.assert_called_once_with()
            self.assertEqual(scanner.scan_with_password(get_browser, "icourse163")[0], "ok")
            self.assertIs(auth_status.get_password_status("icourse163"), True)
            adapter.assert_called_once_with(context)

    def test_manual_login_passes_verified_context_directly_to_scan(self):
        context, driver = Mock(), Mock()
        adapter = Mock(return_value=False)
        context.pages = [context.new_page.return_value]
        with (
            patch.object(browser, "launch", return_value=nullcontext(driver)),
            patch.object(browser, "new_context", return_value=context) as fresh,
            patch.object(browser, "verify_login", return_value=True) as verify,
            patch("builtins.input", return_value=""),
            patch("builtins.print"),
        ):
            self.assertEqual(browser.login(["chaoxing"], "msedge", adapter), 0)
        fresh.assert_called_once_with(driver)
        verify.assert_called_once_with(context, "chaoxing")
        adapter.assert_called_once_with(context, "chaoxing")
        context.close.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
