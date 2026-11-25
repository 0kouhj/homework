import json
import os
import re
import sys
from contextlib import contextmanager
from pathlib import Path
from tempfile import NamedTemporaryFile

from playwright.sync_api import Error, sync_playwright

if getattr(sys, "frozen", False):
    ROOT = Path(sys.executable).resolve().parent
else:
    ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / ".state"
LOGIN = {
    "chaoxing": (
        "学习通",
        "https://i.mooc.chaoxing.com/space/index",
    ),
    "icourse163": (
        "中国大学 MOOC",
        "https://www.icourse163.org/passport/logingate/changeCookie.htm"
        "?type=study&returnUrl=aHR0cHM6Ly93d3cuaWNvdXJzZTE2My5vcmcvP2Zyb209c3R1ZHk&loginWay=0",
    ),
}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with NamedTemporaryFile(
        mode="w", encoding="utf-8", dir=path.parent, delete=False, suffix=".tmp"
    ) as file:
        temp = Path(file.name)
        try:
            json.dump(data, file, ensure_ascii=False, indent=2)
            file.write("\n")
            file.close()
            temp.replace(path)
        finally:
            file.close()
            temp.unlink(missing_ok=True)


@contextmanager
def file_lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as file:
        try:
            if os.name == "nt":
                import msvcrt

                file.seek(0)
                msvcrt.locking(file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            yield False
            return
        yield True


@contextmanager
def launch(*, headless=True, channel="msedge"):
    temp = STATE / "tmp"
    temp.mkdir(parents=True, exist_ok=True)
    previous = {key: os.environ.get(key) for key in ("TEMP", "TMP", "TMPDIR")}
    for key in previous:
        os.environ[key] = str(temp)
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel=channel, headless=headless)
            try:
                yield browser
            finally:
                browser.close()
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def new_context(browser):
    context = browser.new_context(
        locale="zh-CN",
        timezone_id="Asia/Shanghai",
        accept_downloads=False,
    )
    context.set_default_timeout(10000)
    context.set_default_navigation_timeout(18000)
    return context


def verify_login(context, platform):
    page = context.new_page()
    try:
        if platform == "chaoxing":
            page.goto(LOGIN["chaoxing"][1], wait_until="domcontentloaded")
            page.frame_locator("iframe[name=frame_content]").get_by_text(
                "我学的课", exact=True
            ).first.wait_for()
        else:
            page.goto("https://www.icourse163.org/home.htm", wait_until="domcontentloaded")
            page.get_by_text(re.compile(r"^MOOC\s*课程\s*[（(]\d+[）)]$")).first.wait_for()
        return True
    except Error:
        return False
    finally:
        page.close()


def login(platforms, channel, scan):
    with launch(headless=False, channel=channel) as browser:
        contexts = {}
        for platform in platforms:
            label, url = LOGIN[platform]
            context = new_context(browser)
            contexts[platform] = context
            page = context.new_page()
            try:
                page.goto(url, wait_until="domcontentloaded", timeout=20000)
            except Error:
                print(f"{label}入口加载超时，可以在打开的浏览器窗口继续。", flush=True)
            print(f"已打开{label}官方页面，请在浏览器内完成登录。", flush=True)

        print("登录完成后回到终端按 Enter 验证并扫描。输入 q 可取消。", flush=True)
        if input().strip().lower() == "q":
            return 1
        failures = 0
        for platform, context in contexts.items():
            label = LOGIN[platform][0]
            if not context.pages:
                print(f"{label}窗口已关闭，本次未扫描。", flush=True)
                failures += 1
            elif not verify_login(context, platform):
                print(f"{label}未能确认已登录，可以重新运行 login。", flush=True)
                failures += 1
            else:
                failures += scan(context, platform)
            context.close()
        return int(failures > 0)
