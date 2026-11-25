import argparse
import json
import sys
from contextlib import ExitStack
from datetime import datetime
from time import sleep

from playwright.sync_api import Error

from . import chaoxing, icourse163
from .browser import LOGIN, STATE, file_lock, launch, login, new_context
from .credentials import read_credentials
from .dates import CHINA, remaining_time
from .model import LoginRequired, PageChanged, Scan
from .reauth import (
    configure_credentials, management_lock, record_checked_account, remember_password_status, submit_password,
)
from .store import now, read_snapshot, update_snapshot

ADAPTERS = {"chaoxing": chaoxing.scan, "icourse163": icourse163.scan}
KINDS = {"assignment": "作业", "quiz": "测验", "exam": "考试"}
STATUSES = {
    "ok": "扫描完成",
    "partial": "扫描不完整",
    "login_required": "需要登录",
    "error": "扫描失败",
}


def scan_platform(context, platform):
    try:
        result = ADAPTERS[platform](context)
        return ("partial" if result.issues else "ok"), result
    except LoginRequired:
        return "login_required", Scan(issues=["本轮登录会话失效。"])
    except (Error, PageChanged, ValueError) as exc:
        return "error", Scan(issues=[f"平台页面或会话无法读取（{type(exc).__name__}）"])


def scan_with_password(get_browser, platform):
    context = None
    try:
        with management_lock(platform) as acquired:
            if not acquired:
                return "login_required", Scan(issues=["凭据正在更新，可稍后重新扫描。"])
            try:
                credentials = read_credentials(platform)
            except (OSError, UnicodeError):
                return "error", Scan(issues=["无法读取 Windows 凭据。"])
            if credentials is None:
                return "login_required", Scan(issues=[
                    "尚未保存账号密码。请在软件的「平台连接」中点击「账号」设置。",
                ])
            browser = get_browser()
            try:
                context = new_context(browser)
                authenticated = submit_password(context, platform, *credentials)
            except Error:
                remember_password_status(platform, False)
                return "error", Scan(issues=["无法打开本轮登录页面。"])
            remember_password_status(platform, authenticated)
            if not authenticated:
                return "login_required", Scan(issues=["密码登录未通过，请检查账号密码或在官网完成验证。"])
        outcome = scan_platform(context, platform)
        if outcome[0] == "login_required":
            record_checked_account(platform, credentials, False)
        return outcome
    finally:
        if context is not None:
            context.close()


def report_scan(snapshot, platform, started_at, outcome):
    status, result = outcome
    label = LOGIN[platform][0]
    metadata = update_snapshot(snapshot, platform, status, result, started_at)
    print(
        f"{label} · {STATUSES[status]} · 已读 {result.scanned_course_count}"
        f"/{result.course_count} 门课程 · {metadata['task_count']} 条任务",
        flush=True,
    )
    if result.unopened_course_count:
        print(f"  {result.unopened_course_count} 门课程尚未开放，下轮继续检查。", flush=True)
    for issue in result.issues:
        print(f"  {issue}", flush=True)
    if status == "login_required":
        print(
            f"  手动登录并扫描：uv run --locked python -m homework_scan login --platform {platform}",
            flush=True,
        )
    return status != "ok"


def scan_once(platforms, channel):
    with file_lock(STATE / "scan.lock") as acquired, ExitStack() as stack:
        if not acquired:
            print("另一个扫描正在运行。", flush=True)
            return 1
        snapshot = read_snapshot()
        browser = None

        def get_browser():
            nonlocal browser
            if browser is None:
                browser = stack.enter_context(launch(channel=channel))
            return browser

        failures = 0
        for platform in platforms:
            started_at = now()
            print(f"{started_at} 开始扫描{LOGIN[platform][0]}", flush=True)
            outcome = scan_with_password(get_browser, platform)
            failures += report_scan(snapshot, platform, started_at, outcome)
        return int(failures > 0)


def login_and_scan(platforms, channel):
    with file_lock(STATE / "scan.lock") as acquired:
        if not acquired:
            print("另一个扫描正在运行。", flush=True)
            return 1
        snapshot = read_snapshot()

        def scan(context, platform):
            started_at = now()
            print(f"{started_at} 开始扫描{LOGIN[platform][0]}", flush=True)
            return report_scan(snapshot, platform, started_at, scan_platform(context, platform))

        return login(platforms, channel, scan)


def show_tasks(as_json):
    snapshot = read_snapshot()
    as_of = datetime.now(CHINA)
    if as_json:
        snapshot["remaining_as_of"] = as_of.isoformat(timespec="seconds")
        for course in snapshot["courses"]:
            for task in course["tasks"]:
                task["remaining"] = remaining_time(task["due_at"], as_of)
        print(json.dumps(snapshot, ensure_ascii=False, indent=2))
        return
    for platform, info in snapshot["platforms"].items():
        print(f"{LOGIN[platform][0]} · {STATUSES[info['status']]} · {info['last_attempt_at']}")
    for course in snapshot["courses"]:
        print(f"\n{course['course_name']} · {LOGIN[course['platform']][0]}")
        for task in course["tasks"]:
            when = task["due_at"] or task["opens_at"] or "—"
            if task["due_precision"] == "estimated":
                when += "（估算）"
            if task["opens_at"] and not task["due_at"]:
                when += "（开始）"
            status = f" · {task['status']}" if task["status"] else ""
            remaining = remaining_time(task["due_at"], as_of)["text"]
            print(f"  {when} · {KINDS[task['kind']]} · {task['title']} · {remaining}{status}")
    if not snapshot["courses"]:
        print("本地暂无任务记录。")


def main():
    if sys.stdout is not None and not sys.stdout.isatty():
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="扫描学习通和中国大学 MOOC 的作业、测验与考试")
    commands = parser.add_subparsers(dest="command", required=True)
    for name, help_text in (
        ("login", "在平台官网手动登录并扫描一次"),
        ("scan", "扫描一次并更新本地记录"),
        ("watch", "立即扫描并定时重复"),
        ("gui", "打开课程任务界面"),
    ):
        command = commands.add_parser(name, help=help_text)
        if name != "gui":
            command.add_argument("--platform", choices=("all", *LOGIN), default="all")
        command.add_argument("--browser", choices=("msedge", "chrome"), default="msedge")
        if name in ("watch", "gui"):
            command.add_argument("--interval-minutes", type=int, default=15, help="扫描间隔，默认 15")
        if name == "gui":
            command.add_argument("--no-auto-scan", action="store_true", help="启动时暂停定时扫描")
    commands.add_parser("list", help="查看本地缓存").add_argument("--json", action="store_true")
    credentials = commands.add_parser("credentials", help="管理 Windows 凭据管理器中的扫描账号密码")
    credentials.add_argument("action", choices=("set", "clear"))
    credentials.add_argument("--platform", choices=tuple(LOGIN), required=True)
    args = parser.parse_args()
    if args.command in ("watch", "gui") and args.interval_minutes < 1:
        parser.error("扫描间隔至少为 1 分钟")
    if args.command == "gui":
        from .gui import run_gui

        return run_gui(args.browser, args.interval_minutes, auto_scan=not args.no_auto_scan)
    if args.command == "list":
        show_tasks(args.json)
        return 0
    if args.command == "credentials":
        return configure_credentials(args.action, args.platform)
    platforms = list(LOGIN) if args.platform == "all" else [args.platform]
    try:
        if args.command == "login":
            return login_and_scan(platforms, args.browser)
        if args.command == "scan":
            return scan_once(platforms, args.browser)
        while True:
            try:
                scan_once(platforms, args.browser)
            except Error:
                print("浏览器暂时无法启动，下轮会重试。", flush=True)
            sleep(args.interval_minutes * 60)
    except KeyboardInterrupt:
        return 0
    except Error:
        print("浏览器无法启动或连接。请确认所选的 Edge 或 Chrome 可用。", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
