"""Sign in to each platform with the account in Windows Credential Manager."""

import re
import sys
from getpass import getpass
from time import monotonic
from urllib.parse import urlsplit

from playwright.sync_api import Error

from . import browser
from .auth_status import reset_password_status, set_password_status
from .browser import LOGIN
from .credentials import delete_credentials, read_credentials, write_credentials

MOOC_HOME = "https://www.icourse163.org/home.htm"
MOOC_LOGIN = re.compile(r"^登录\s*\|\s*注册$")
MOOC_COURSES = re.compile(r"^MOOC\s*课程\s*[（(]\d+[）)]$")


def management_lock(platform):
    return browser.file_lock(browser.STATE / "auth" / f"{platform}.password.lock")


def remember_password_status(platform, verified):
    try:
        set_password_status(platform, verified)
    except OSError:
        pass


def record_checked_account(platform, account, verified):
    username, password = account
    candidate = (username.strip(), password)
    try:
        with management_lock(platform) as acquired:
            if acquired and read_credentials(platform) == candidate:
                remember_password_status(platform, verified)
    except (OSError, UnicodeError):
        pass


def change_credentials(platform, account, *, verified=None):
    if account is not None:
        username, password = account
        username = username.strip()
        if not username or not password:
            return "账号和密码不能为空。"
    try:
        with management_lock(platform) as acquired:
            if not acquired:
                return "正在使用该平台的账号登录，请稍后再试。"
            reset_password_status(platform)
            if account is None:
                delete_credentials(platform)
            else:
                write_credentials(platform, username, password)
                if verified is not None:
                    remember_password_status(platform, verified)
    except (OSError, ValueError):
        return "凭据操作失败，请检查 Windows 凭据管理器。"
    return None


def configure_credentials(action, platform):
    label = LOGIN[platform][0]
    account = None
    if action == "set":
        if not sys.stdin.isatty():
            print("请在本机交互式终端输入账号密码。")
            return 1
        print(f"保存后，扫描器可在{label}官网登录页自动提交此账号密码。")
        username = input("账号（手机号、超星号或邮箱，以平台支持为准）：")
        password = getpass("密码（输入时不显示）：")
        account = (username, password)
    if issue := change_credentials(platform, account):
        print(f"{label} · {issue}")
        return 1
    if action == "set":
        print(f"{label}凭据已保存到 Windows 凭据管理器。")
    else:
        print(f"{label}已删除保存的账号密码。")
    return 0


def submit_chaoxing(page, username, password):
    page.goto(LOGIN["chaoxing"][1], wait_until="domcontentloaded")
    url = urlsplit(page.url)
    if (url.scheme, url.hostname, url.path) != ("https", "passport2.chaoxing.com", "/login"):
        return False
    page.locator("input#phone").fill(username)
    page.locator("input#pwd[type=password]").fill(password)
    page.locator("button#loginBtn").click()
    page.frame_locator("iframe[name=frame_content]").get_by_text(
        "我学的课", exact=True
    ).first.wait_for(timeout=12000)
    return True


def mooc_password_frame(page, account_selector, submit_selector):
    until = monotonic() + 10
    while monotonic() < until:
        for frame in page.frames:
            target = urlsplit(frame.url)
            if (
                target.scheme == "https"
                and target.hostname == "reg.icourse163.org"
                and target.path.endswith("/pub/index_dl2_new.html")
                and frame.locator(account_selector).first.is_visible()
                and frame.locator(submit_selector).is_visible()
            ):
                return frame
        page.wait_for_timeout(150)
    return None


def submit_mooc(page, username, password):
    page.goto(MOOC_HOME, wait_until="domcontentloaded")
    url = urlsplit(page.url)
    if (url.scheme, url.hostname, url.path) != ("https", "www.icourse163.org", "/home.htm"):
        return False
    page.get_by_text(MOOC_LOGIN).filter(visible=True).first.click()
    email = "@" in username
    if email:
        mode, account, submit = "邮箱登录", 'input[name=email][type=text]', "a#dologin"
    else:
        mode, account, submit = "手机号登录", "input#phoneipt", "a#submitBtn"
    page.get_by_text(mode, exact=True).filter(visible=True).first.click()
    frame = mooc_password_frame(page, account, submit)
    if frame is None:
        return False
    frame.locator(account).first.fill(username)
    frame.locator("input[type=password]:visible").first.fill(password)
    frame.locator(submit).click()
    page.get_by_text(MOOC_COURSES).filter(visible=True).first.wait_for(timeout=12000)
    return True


def submit_password(context, platform, username, password):
    page = context.new_page()
    try:
        handler = submit_chaoxing if platform == "chaoxing" else submit_mooc
        return handler(page, username, password)
    except Error:
        return False
    finally:
        page.close()


def check_password_login(platform, channel, username, password):
    username = username.strip()
    if not username or not password:
        return "请输入账号和密码。"

    try:
        with browser.launch(channel=channel) as driver:
            context = browser.new_context(driver)
            try:
                if submit_password(context, platform, username, password):
                    return None
            finally:
                context.close()
    except (Error, OSError):
        return "无法运行登录验证。请检查浏览器和网络连接后重试。"
    return "未能确认登录成功。请检查账号密码，或在官网完成额外验证。"
