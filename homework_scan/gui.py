"""Native Windows dashboard for the local course scanner."""

import json
import subprocess
import sys
import tkinter as tk
import webbrowser
from calendar import monthrange
from datetime import datetime
from queue import Empty, Queue
from threading import Thread
from time import monotonic
from tkinter import messagebox, ttk
from urllib.parse import urlsplit

from .auth_status import get_password_status
from .browser import LOGIN, ROOT
from .credentials import read_credential_username
from .dates import CHINA, remaining_time
from .model import group_tasks
from .reauth import change_credentials, check_password_login, record_checked_account
from .store import OUTPUT, read_snapshot

FONT = "Microsoft YaHei UI"
BACKGROUND = "#f2f5f8"
WHITE = "#ffffff"
INK = "#172940"
MUTED = "#54677a"
LINE = "#dce5ee"
NAVY = "#132338"
NAV_TEXT = "#c0cede"
ACCENT = "#006f6d"
ACCENT_LIGHT = "#e5f5f2"
GROUP = "#edf2f7"
KIND = {"assignment": "作业", "quiz": "测验", "exam": "考试"}
STATUS = {
    "ok": ("扫描完成", "#6cdbbd"),
    "partial": ("扫描不完整", "#ffce7a"),
    "login_required": ("需要登录", "#ffce7a"),
    "error": ("扫描失败", "#ffa29b"),
}
PASSWORD_STATUS = {
    None: ("待验证", NAV_TEXT),
    True: ("验证通过", "#6cdbbd"),
    False: ("登录未确认", "#ffce7a"),
}
RANGES = (
    ("all", "全部任务", INK),
    ("remaining", "未到期", ACCENT),
    ("elapsed", "已截止", "#9a483c"),
    ("unknown", "截止时间未知", "#526883"),
)
DESTINATIONS = {"course": "课程 ↗", "section": "列表 ↗", "task": "任务 ↗"}
ACCOUNT_HINTS = {"chaoxing": "手机号或超星号", "icourse163": "手机号或邮箱"}


def clickable_url(task):
    address = task.get("open_url", "")
    try:
        url = urlsplit(address or "")
        port = url.port
    except ValueError:
        return None
    host = url.hostname or ""
    if task["platform"] == "chaoxing":
        allowed = host == "chaoxing.com" or host.endswith(".chaoxing.com")
    else:
        allowed = task["platform"] == "icourse163" and host == "www.icourse163.org"
    if allowed and url.scheme == "https" and port in (None, 443) and not url.username and not url.password:
        return address
    return None


def display_task_time(task):
    value = task["due_at"] or task["opens_at"]
    if not value:
        return "—"
    if len(value) > 10:
        value = datetime.fromisoformat(value).astimezone(CHINA).strftime("%Y-%m-%d  %H:%M")
    if not task["due_at"]:
        return value + " 开始"
    return value + ("  ≈" if task.get("due_precision") == "estimated" else "")


def launch_backend(words, events):
    command = [sys.executable]
    if not getattr(sys, "frozen", False):
        command.extend(("-X", "utf8", "-m", "homework_scan"))
    try:
        with subprocess.Popen(
            [*command, *words],
            cwd=ROOT,
            stdin=subprocess.PIPE if words[0] == "login" else subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            encoding="utf-8",
            errors="replace",
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        ) as process:
            for output in process.stdout:
                line = output.strip()
                if words[0] == "login" and line.startswith("登录完成后回到终端"):
                    events.put(("login_ready", process))
                elif line:
                    events.put(("output", line))
            events.put(("done", process.wait()))
    except OSError:
        events.put(("output", "无法启动扫描程序。"))
        events.put(("done", 1))


def run_account_check(platform, channel, account, events):
    issue = check_password_login(platform, channel, *account)
    record_checked_account(platform, account, issue is None)
    events.put(("account_checked", issue))


def password_connection(platform):
    try:
        if read_credential_username(platform) is None:
            return "未设置账号", NAV_TEXT
    except (OSError, UnicodeError):
        return "凭据不可读", "#ffa29b"
    return PASSWORD_STATUS[get_password_status(platform)]


class Dashboard:
    def __init__(self, root, browser, interval, auto_scan):
        self.root = root
        self.browser = browser
        self.interval = interval
        self.automatic = tk.BooleanVar(value=auto_scan)
        self.query = tk.StringVar()
        self.kind = tk.StringVar(value="全部类型")
        self.platform = "all"
        self.date_range = "remaining"
        self.events = Queue()
        self.snapshot = {"courses": [], "platforms": {}}
        self.snapshot_mtime = None
        self.snapshot_error = False
        self.next_scan = monotonic() if auto_scan else None
        self.next_view = 0
        self.job = None
        self.login_process = None
        self.login_dialog = None
        self.account_dialog = None
        self.account_check = None
        self.last_output = ""
        self.rows = {}
        self.group_order = ()
        self.collapsed = set()

        root.title("课业日程 · 课程任务")
        root.geometry("1220x790")
        root.minsize(980, 600)
        root.configure(bg=BACKGROUND)
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.create_styles()
        self.build()
        self.refresh_snapshot()
        self.render(reset_scroll=True)
        self.refresh_connection_status()
        root.after(150, self.tick)

    def create_styles(self):
        style = ttk.Style(self.root)
        style.theme_use("clam")
        style.configure(
            "Tasks.Treeview", background=WHITE, fieldbackground=WHITE,
            foreground=INK, rowheight=37, font=(FONT, 10), borderwidth=0,
        )
        style.configure(
            "Tasks.Treeview.Heading", background="#f6f8fb", foreground=MUTED,
            font=(FONT, 10, "bold"), padding=(10, 10), borderwidth=0,
        )
        style.map("Tasks.Treeview", background=[("selected", ACCENT_LIGHT)],
                  foreground=[("selected", "#075751")])
        style.configure(
            "Filter.TCombobox", fieldbackground=WHITE, background=WHITE,
            foreground=INK, bordercolor=LINE, padding=5, arrowsize=13,
        )
        style.map("Filter.TCombobox", fieldbackground=[("readonly", WHITE)],
                  foreground=[("readonly", INK)], selectbackground=[("readonly", WHITE)],
                  selectforeground=[("readonly", INK)])
        style.configure("Quiet.Vertical.TScrollbar", background="#c1cfdd", troughcolor=WHITE,
                        bordercolor=WHITE, arrowcolor=MUTED, gripcount=0)

    def build(self):
        sidebar = tk.Frame(self.root, bg=NAVY, width=216)
        sidebar.pack(side="left", fill="y")
        sidebar.pack_propagate(False)
        main = tk.Frame(self.root, bg=BACKGROUND, padx=28, pady=24)
        main.pack(side="left", fill="both", expand=True)
        self.build_sidebar(sidebar)
        self.build_main(main)

    def build_sidebar(self, sidebar):
        logo = tk.Frame(sidebar, bg=NAVY)
        logo.pack(fill="x", padx=20, pady=(27, 32))
        badge = tk.Canvas(logo, width=36, height=36, bg=NAVY, highlightthickness=0)
        badge.pack(side="left", padx=(0, 10))
        badge.create_oval(0, 0, 35, 35, fill="#37c8ac", outline="")
        badge.create_rectangle(10, 10, 25, 24, outline=NAVY, width=2)
        badge.create_line(10, 14, 25, 14, fill=NAVY, width=2)
        badge.create_line(14, 8, 14, 12, fill=NAVY, width=2)
        badge.create_line(21, 8, 21, 12, fill=NAVY, width=2)
        tk.Label(logo, text="课业日程", bg=NAVY, fg=WHITE, font=(FONT, 16, "bold")).pack(side="left")

        tk.Label(sidebar, text="课程来源", bg=NAVY, fg=NAV_TEXT, anchor="w",
                 font=(FONT, 9, "bold")).pack(fill="x", padx=22, pady=(0, 8))
        self.platform_buttons = {}
        for platform, label in [("all", "全部平台"), *((key, item[0]) for key, item in LOGIN.items())]:
            button = tk.Button(
                sidebar, text=label, anchor="w", padx=13, pady=8, relief="flat",
                bd=0, bg=NAVY, fg=NAV_TEXT, activebackground="#284257", activeforeground=WHITE,
                cursor="hand2", font=(FONT, 10, "bold"),
                command=lambda key=platform: self.select_platform(key),
            )
            button.pack(fill="x", padx=15, pady=2)
            self.platform_buttons[platform] = button

        connections = tk.Frame(sidebar, bg=NAVY)
        connections.pack(side="bottom", fill="x", padx=21, pady=(0, 23))
        tk.Frame(connections, height=1, bg="#38495c").pack(fill="x", pady=(0, 16))
        tk.Label(connections, text="平台连接", bg=NAVY, fg=NAV_TEXT, anchor="w",
                 font=(FONT, 9, "bold")).pack(fill="x", pady=(0, 8))
        self.connection_labels = {}
        self.connection_buttons = []
        for platform, (label, _) in LOGIN.items():
            row = tk.Frame(connections, bg=NAVY)
            row.pack(fill="x", pady=5)
            tk.Label(row, text=label, bg=NAVY, fg=WHITE, anchor="w", font=(FONT, 9)).pack(anchor="w")
            status = tk.Button(
                row, bg=NAVY, fg=NAV_TEXT, activebackground="#284257", activeforeground=WHITE,
                relief="flat", bd=0, padx=0, anchor="w", cursor="hand2", font=(FONT, 9),
                command=lambda key=platform: self.show_platform_status(key),
            )
            status.pack(side="left")
            for text, action in (("登录", self.start_login), ("账号", self.show_account_settings)):
                button = tk.Button(
                    row, text=text, bg="#26394e", fg=WHITE, activebackground="#385066",
                    activeforeground=WHITE, relief="flat", bd=0, padx=7, cursor="hand2",
                    font=(FONT, 8), command=lambda key=platform, run=action: run(key),
                )
                button.pack(side="right", padx=(4, 0))
                self.connection_buttons.append(button)
            self.connection_labels[platform] = status

    def build_main(self, main):
        header = tk.Frame(main, bg=BACKGROUND)
        header.pack(fill="x")
        title = tk.Frame(header, bg=BACKGROUND)
        title.pack(side="left")
        tk.Label(title, text="课程任务", bg=BACKGROUND, fg=INK,
                 font=(FONT, 24, "bold")).pack(anchor="w")
        self.updated = tk.Label(title, bg=BACKGROUND, fg=MUTED, font=(FONT, 9))
        self.updated.pack(anchor="w", pady=(3, 0))
        self.scan_button = tk.Button(
            header, text="立即扫描", bg=ACCENT, fg=WHITE, activebackground="#005c59",
            activeforeground=WHITE, disabledforeground=WHITE, relief="flat", bd=0,
            padx=18, pady=9, cursor="hand2", font=(FONT, 10, "bold"), command=self.start_scan,
        )
        self.scan_button.pack(side="right", pady=4)
        tk.Checkbutton(
            header, text=f"每 {self.interval} 分钟扫描", variable=self.automatic,
            command=self.toggle_scan, bg=BACKGROUND, activebackground=BACKGROUND,
            fg=INK, selectcolor=WHITE, highlightthickness=0, font=(FONT, 9),
        ).pack(side="right", padx=(8, 12))

        metrics = tk.Frame(main, bg=BACKGROUND)
        metrics.pack(fill="x", pady=(20, 17))
        self.metrics = {}
        for column, (key, label, color) in enumerate(RANGES):
            metrics.columnconfigure(column, weight=1, uniform="metrics")
            card = tk.Frame(metrics, bg=WHITE, highlightthickness=1, highlightbackground=LINE, takefocus=True)
            card.grid(row=0, column=column, sticky="nsew", padx=(0 if not column else 6, 0 if column == 3 else 6))
            caption = tk.Label(card, text=label, bg=WHITE, fg=MUTED, font=(FONT, 9), anchor="w")
            caption.pack(fill="x", padx=14, pady=(9, 0))
            value = tk.Label(card, text="0", bg=WHITE, fg=color, font=("Segoe UI", 24, "bold"), anchor="w")
            value.pack(fill="x", padx=13, pady=(0, 7))
            for widget in (card, caption, value):
                widget.configure(cursor="hand2")
                widget.bind("<Button-1>", lambda _, name=key: self.select_range(name))
            card.bind("<Return>", lambda _, name=key: self.select_range(name))
            card.bind("<space>", lambda _, name=key: self.select_range(name))
            self.metrics[key] = (card, caption, value)

        panel = tk.Frame(main, bg=WHITE, highlightthickness=1, highlightbackground=LINE)
        panel.pack(fill="both", expand=True)
        filters = tk.Frame(panel, bg=WHITE, padx=14, pady=10)
        filters.pack(fill="x")
        self.result_label = tk.Label(filters, bg=WHITE, fg=INK, font=(FONT, 11, "bold"))
        self.result_label.pack(side="left")
        choice = ttk.Combobox(filters, textvariable=self.kind, state="readonly", width=9,
                              style="Filter.TCombobox", values=("全部类型", *KIND.values()), font=(FONT, 9))
        choice.pack(side="right", padx=(10, 0))
        search_box = tk.Frame(filters, bg="#f6f8fb", highlightthickness=1, highlightbackground=LINE)
        search_box.pack(side="right")
        tk.Label(search_box, text="搜索", bg="#f6f8fb", fg=MUTED, font=(FONT, 9)).pack(side="left", padx=(8, 6))
        search = tk.Entry(search_box, textvariable=self.query, width=18, bd=0, bg="#f6f8fb", fg=INK, font=(FONT, 10))
        search.pack(side="left", ipady=5)
        tk.Button(search_box, text="×", command=lambda: self.query.set(""), bd=0,
                  bg="#f6f8fb", fg=MUTED, activebackground=ACCENT_LIGHT, width=2,
                  font=(FONT, 10), cursor="hand2").pack(side="right", padx=(0, 3))
        self.root.bind("<Control-f>", lambda _: search.focus_set())
        self.query.trace_add("write", self.filters_changed)
        self.kind.trace_add("write", self.filters_changed)

        contents = tk.Frame(panel, bg=WHITE)
        contents.pack(fill="both", expand=True)
        self.tree = ttk.Treeview(
            contents, style="Tasks.Treeview", selectmode="browse",
            columns=("type", "due", "remaining", "target"),
        )
        self.tree.heading("#0", text="课程 / 任务标题", anchor="w")
        self.tree.column("#0", width=330, minwidth=200, stretch=True)
        for field, label, width in (
            ("type", "类型", 60), ("due", "截止 / 开始", 163),
            ("remaining", "剩余时间", 170), ("target", "打开", 70),
        ):
            self.tree.heading(field, text=label, anchor="w")
            self.tree.column(field, width=width, minwidth=width, stretch=False, anchor="w")
        self.tree.tag_configure("course", background=GROUP, foreground=INK, font=(FONT, 10, "bold"))
        self.tree.tag_configure("elapsed", foreground="#6a7583")
        scroll = ttk.Scrollbar(contents, orient="vertical", command=self.tree.yview, style="Quiet.Vertical.TScrollbar")
        self.tree.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.tree.pack(side="left", fill="both", expand=True)
        self.tree.bind("<ButtonRelease-1>", self.activate_row)
        self.tree.bind("<Return>", self.activate_row)
        self.tree.bind("<Motion>", self.update_cursor)
        self.empty = tk.Label(contents, bg=WHITE, fg=MUTED, font=(FONT, 11), justify="center")

        footer = tk.Frame(main, bg=BACKGROUND)
        footer.pack(fill="x", pady=(10, 0))
        self.activity = tk.Label(footer, bg=BACKGROUND, fg=MUTED, font=(FONT, 9))
        self.activity.pack(side="left")
        tk.Label(footer, text="北京时间 · ≈ 为估算截止时间", bg=BACKGROUND, fg=MUTED,
                 font=(FONT, 9)).pack(side="right")

    def select_platform(self, platform):
        self.platform = platform
        self.render(reset_scroll=True)

    def select_range(self, date_range):
        self.date_range = date_range
        self.render(reset_scroll=True)

    def filters_changed(self, *_):
        self.render(reset_scroll=True)

    def refresh_snapshot(self):
        try:
            stamp = OUTPUT.stat().st_mtime_ns if OUTPUT.exists() else None
            if stamp != self.snapshot_mtime:
                self.snapshot = read_snapshot()
                self.snapshot_mtime = stamp
                self.snapshot_error = False
                return True
        except (OSError, json.JSONDecodeError):
            self.snapshot_error = True
        return False

    def refresh_connection_status(self):
        for platform, widget in self.connection_labels.items():
            label, color = password_connection(platform)
            widget.configure(text="●  " + label, fg=color)

    def render(self, *, reset_scroll=False):
        as_of = datetime.now(CHINA)
        previous_year = as_of.year - 1
        cutoff_day = min(as_of.day, monthrange(previous_year, as_of.month)[1])
        start_cutoff = as_of.date().replace(year=previous_year, day=cutoff_day).isoformat()
        query = self.query.get().strip().casefold()
        task_type = self.kind.get()
        filtered = []
        remaining_by_id = {}
        counts = dict.fromkeys(("all", "remaining", "elapsed", "unknown"), 0)
        for course in self.snapshot["courses"]:
            if self.platform not in ("all", course["platform"]):
                continue
            course_matches = query in course["course_name"].casefold()
            for task in course["tasks"]:
                if task["opens_at"] and task["opens_at"][:10] <= start_cutoff:
                    continue
                if not course_matches and query not in task["title"].casefold():
                    continue
                if task_type != "全部类型" and KIND.get(task["kind"]) != task_type:
                    continue
                remaining = remaining_time(task["due_at"], as_of)
                if task["kind"] == "exam" and task.get("status") == "已完成":
                    remaining.update(state="elapsed", text="已完成")
                bucket = "remaining" if remaining["state"] == "today" else remaining["state"]
                counts["all"] += 1
                counts[bucket] += 1
                if self.date_range in ("all", bucket):
                    filtered.append(task)
                    remaining_by_id[task["id"]] = remaining

        for platform, button in self.platform_buttons.items():
            active = platform == self.platform
            button.configure(bg="#2b465d" if active else NAVY, fg=WHITE if active else NAV_TEXT)
        for key, (card, caption, value) in self.metrics.items():
            active = key == self.date_range
            color = ACCENT_LIGHT if active else WHITE
            card.configure(bg=color, highlightbackground=ACCENT if active else LINE)
            caption.configure(bg=color)
            value.configure(bg=color, text=str(counts[key]))

        groups = group_tasks(filtered)
        self.result_label.configure(text=f"{len(groups)} 门课程  ·  {len(filtered)} 项任务")
        self.render_rows(groups, remaining_by_id, reset_scroll)
        timestamp = self.snapshot.get("updated_at")
        text = "尚未完成首次扫描"
        if timestamp:
            text = "上次更新  " + datetime.fromisoformat(timestamp).astimezone(CHINA).strftime("%m-%d %H:%M")
        self.updated.configure(text=text)

    def render_rows(self, groups, remaining_by_id, reset_scroll):
        new_order = tuple(
            (group["platform"], group["course_id"], group["course_name"],
             tuple(task["id"] for task in group["tasks"]))
            for group in groups
        )
        rebuild = new_order != self.group_order
        position = self.tree.yview()[0]
        selected = self.tree.selection()
        if rebuild:
            old_groups = self.tree.get_children()
            for key in old_groups:
                if self.tree.item(key, "open"):
                    self.collapsed.discard(key)
                else:
                    self.collapsed.add(key)
            self.tree.delete(*old_groups)
            self.rows.clear()
            self.group_order = new_order
        for group in groups:
            platform = group["platform"]
            key = f"{platform}:{group['course_id']}:{group['course_name']}"
            label = f"{group['course_name']}  ·  {LOGIN[platform][0]}"
            if rebuild:
                self.tree.insert("", "end", iid=key, text=label, open=key not in self.collapsed,
                                 values=(f"{len(group['tasks'])} 项",), tags=("course",))
            for task in group["tasks"]:
                task_id = task["id"]
                self.rows[task_id] = task
                remaining = remaining_by_id[task_id]
                destination = DESTINATIONS.get(task.get("open_scope"), "网页 ↗") if clickable_url(task) else "—"
                values = (KIND.get(task["kind"], ""), display_task_time(task), remaining["text"], destination)
                tags = ("elapsed",) if remaining["state"] == "elapsed" else ()
                if rebuild:
                    self.tree.insert(key, "end", iid=task_id, text=task["title"], values=values, tags=tags)
                else:
                    self.tree.item(task_id, text=task["title"], values=values, tags=tags)
        if rebuild and selected and self.tree.exists(selected[0]):
            self.tree.selection_set(selected[0])
        if rebuild or reset_scroll:
            self.tree.yview_moveto(0 if reset_scroll else position)
        if groups:
            self.empty.place_forget()
        else:
            text = "当前暂无符合条件的任务\n可切换「全部任务」或清空搜索"
            if not self.snapshot["courses"]:
                text = "本地暂无任务\n登录平台后点击「立即扫描」"
            self.empty.configure(text=text)
            self.empty.place(relx=0.5, rely=0.5, anchor="center")

    def update_cursor(self, event):
        row = self.tree.identify_row(event.y)
        task = self.rows.get(row)
        self.tree.configure(cursor="hand2" if task and clickable_url(task) else "")

    def activate_row(self, event):
        if event.keysym == "Return":
            row = self.tree.focus()
        elif self.tree.identify_region(event.x, event.y) in ("tree", "cell"):
            row = self.tree.identify_row(event.y)
        else:
            return
        if task := self.rows.get(row):
            if url := clickable_url(task):
                try:
                    if not webbrowser.open_new_tab(url):
                        raise OSError
                except OSError:
                    messagebox.showerror("打开网页", "系统浏览器未能打开这个地址。", parent=self.root)

    def show_platform_status(self, platform):
        label = LOGIN[platform][0]
        info = self.snapshot["platforms"].get(platform)
        connection, _ = password_connection(platform)
        details = [f"密码登录 · {connection}"]
        if info:
            title = STATUS.get(info.get("status"), ("未知状态", ""))[0]
            coverage = f"已读 {info.get('scanned_course_count', 0)}/{info.get('course_count', 0)} 门课程"
            details.extend([f"上次扫描 · {title}", coverage, *info.get("issues", [])])
        else:
            details.append("尚未扫描。")
        messagebox.showinfo(label, "\n".join(details), parent=self.root)

    def toggle_scan(self):
        self.next_scan = monotonic() if self.automatic.get() else None

    def start_scan(self, platform="all"):
        self.start_job("scan", platform)

    def start_login(self, platform):
        self.start_job("login", platform)

    def show_account_settings(self, platform):
        label = LOGIN[platform][0]
        title = f"{label}账号设置"
        try:
            saved_username = read_credential_username(platform)
            status = "尚未设置账号密码"
            if saved_username is not None:
                status = f"已保存账号密码 · {PASSWORD_STATUS[get_password_status(platform)][0]}"
            can_clear = saved_username is not None
        except (OSError, UnicodeError):
            saved_username = ""
            status = "暂时无法读取已保存的账号"
            can_clear = True

        dialog = tk.Toplevel(self.root, bg=WHITE)
        self.account_dialog = dialog
        dialog.title(title)
        dialog.transient(self.root)
        dialog.resizable(False, False)

        body = tk.Frame(dialog, bg=WHITE, padx=24, pady=18)
        body.pack(fill="both", expand=True)
        tk.Label(body, text=status, bg=WHITE, fg=MUTED, font=(FONT, 10)).pack(anchor="w", pady=(0, 12))
        tk.Label(body, text=f"账号（{ACCOUNT_HINTS[platform]}）", bg=WHITE, fg=INK,
                 font=(FONT, 10)).pack(anchor="w")
        username = ttk.Entry(body, width=33, font=(FONT, 10))
        username.insert(0, saved_username or "")
        username.pack(fill="x", pady=(4, 12))
        tk.Label(body, text="密码", bg=WHITE, fg=INK, font=(FONT, 10)).pack(anchor="w")
        password = ttk.Entry(body, show="●", width=33, font=(FONT, 10))
        password.pack(fill="x", pady=(4, 12))
        tk.Label(body, text="点击「保存」后，扫描器会在下次扫描使用此账号。",
                 bg=WHITE, fg=MUTED, font=(FONT, 9)).pack(anchor="w")
        feedback = tk.Label(body, bg=WHITE, fg=MUTED, font=(FONT, 9),
                            anchor="w", justify="left", wraplength=310)
        feedback.pack(fill="x", pady=(8, 0))
        verified = None

        def clear_feedback():
            nonlocal verified
            verified = None
            feedback.configure(text="")
            return True

        clear_on_edit = dialog.register(clear_feedback)
        for entry in (username, password):
            entry.configure(validate="key", validatecommand=clear_on_edit)

        def close_dialog():
            password.state(["!disabled"])
            password.delete(0, tk.END)
            dialog.destroy()
            self.account_dialog = None

        def save(_event=None):
            if save_button.instate(["disabled"]):
                return
            if issue := change_credentials(platform, (username.get(), password.get()), verified=verified):
                messagebox.showerror(title, issue, parent=dialog)
                return
            self.last_output = f"{label}账号已保存，下次扫描生效。"
            self.refresh_connection_status()
            close_dialog()

        def clear():
            if not messagebox.askyesno(title, f"清除已保存的{label}账号密码？", parent=dialog):
                return
            if issue := change_credentials(platform, None):
                messagebox.showerror(title, issue, parent=dialog)
                return
            self.last_output = f"{label}账号密码已清除。"
            self.refresh_connection_status()
            close_dialog()

        def complete_check(issue):
            nonlocal verified
            self.refresh_connection_status()
            if self.account_dialog is not dialog:
                return
            verified = issue is None
            for control in check_controls:
                control.state(["!disabled"])
            if can_clear:
                clear_button.state(["!disabled"])
            feedback.configure(
                text=issue or "登录成功。点击「保存」后可用于扫描。",
                fg="#9a483c" if issue else ACCENT,
            )

        def verify():
            if self.account_check is not None:
                feedback.configure(text="还有一次登录验证正在进行，请稍后再试。", fg=MUTED)
                return
            account = (username.get(), password.get())
            for control in (*check_controls, clear_button):
                control.state(["disabled"])
            feedback.configure(text="正在尝试登录官网…", fg=MUTED)
            self.account_check = complete_check
            # Finish closing the verification browser even if the app window is dismissed.
            Thread(target=run_account_check, args=(platform, self.browser, account, self.events)).start()

        actions = tk.Frame(body, bg=WHITE)
        actions.pack(fill="x", pady=(16, 0))
        save_button = ttk.Button(actions, text="保存", command=save)
        save_button.pack(side="right")
        ttk.Button(actions, text="取消", command=close_dialog).pack(side="right", padx=8)
        clear_button = ttk.Button(actions, text="清除密码", command=clear, state="normal" if can_clear else "disabled")
        clear_button.pack(side="left")
        verify_button = ttk.Button(actions, text="验证登录", command=verify)
        verify_button.pack(side="left", padx=(8, 0))
        check_controls = (username, password, verify_button, save_button)
        dialog.protocol("WM_DELETE_WINDOW", close_dialog)
        dialog.bind("<Escape>", lambda _: close_dialog())
        dialog.bind("<Return>", save)
        dialog.grab_set()
        (password if saved_username else username).focus_set()

    def start_job(self, command, platform):
        if self.job is not None or self.account_dialog is not None or self.account_check is not None:
            return
        self.job = (command, platform)
        self.last_output = "正在打开官网登录" if command == "login" else "正在启动扫描"
        self.scan_button.configure(state="disabled", bg="#477675",
                                   text="扫描中…" if command == "scan" else "登录中…")
        for button in self.connection_buttons:
            button.configure(state="disabled")
        words = [command, "--platform", platform, "--browser", self.browser]
        Thread(target=launch_backend, args=(words, self.events), daemon=True).start()

    def show_login_dialog(self, process):
        self.login_process = process
        label = LOGIN[self.job[1]][0]
        dialog = tk.Toplevel(self.root, bg=WHITE)
        self.login_dialog = dialog
        dialog.title(f"登录{label}")
        dialog.transient(self.root)
        dialog.resizable(False, False)
        tk.Label(dialog, text=f"请在浏览器中登录{label}", bg=WHITE, fg=INK,
                 font=(FONT, 12, "bold"), padx=24, pady=18).pack(anchor="w")
        tk.Label(dialog, text="登录完成后保持浏览器打开，回来点击「验证并扫描」。",
                 bg=WHITE, fg=MUTED, font=(FONT, 10), padx=24).pack(anchor="w")
        actions = tk.Frame(dialog, bg=WHITE, padx=20, pady=16)
        actions.pack(fill="x")
        tk.Button(actions, text="验证并扫描", command=lambda: self.finish_login(True),
                  bg=ACCENT, fg=WHITE, relief="flat", padx=12, pady=5, font=(FONT, 9)).pack(side="right")
        tk.Button(actions, text="取消", command=lambda: self.finish_login(False),
                  bg=WHITE, fg=INK, relief="flat", padx=12, pady=5, font=(FONT, 9)).pack(side="right", padx=8)
        dialog.protocol("WM_DELETE_WINDOW", lambda: self.finish_login(False))

    def finish_login(self, confirmed):
        if self.login_process:
            try:
                self.login_process.stdin.write("\n" if confirmed else "q\n")
                self.login_process.stdin.flush()
            except (OSError, ValueError):
                pass
        if self.login_dialog:
            self.login_dialog.destroy()
        self.login_process = self.login_dialog = None

    def poll_jobs(self):
        while True:
            try:
                event, value = self.events.get_nowait()
            except Empty:
                return
            if event == "output":
                self.last_output = value
            elif event == "login_ready":
                self.show_login_dialog(value)
            elif event == "account_checked":
                complete_check = self.account_check
                self.account_check = None
                complete_check(value)
            else:
                self.job = None
                self.refresh_connection_status()
                self.scan_button.configure(state="normal", bg=ACCENT, text="立即扫描")
                for button in self.connection_buttons:
                    button.configure(state="normal")
                if self.automatic.get():
                    self.next_scan = monotonic() + self.interval * 60
                if not value:
                    self.last_output = "扫描完成"

    def tick(self):
        self.poll_jobs()
        current = monotonic()
        if self.refresh_snapshot() or current >= self.next_view:
            self.render()
            self.refresh_connection_status()
            self.next_view = current + 15
        if self.job is None and self.next_scan is not None and current >= self.next_scan:
            self.start_scan()
        status = self.last_output
        if self.job is None and self.next_scan is not None:
            seconds = max(0, int(self.next_scan - current) + 1)
            minutes, seconds = divmod(seconds, 60)
            status = f"下次扫描 {minutes:02d}:{seconds:02d}" + (f"  ·  {status}" if status else "")
        if self.snapshot_error:
            status = "暂时无法读取本地记录"
        if len(status) > 52:
            status = status[:51] + "…"
        self.activity.configure(text=status or "定时扫描已暂停")
        self.root.after(350, self.tick)

    def close(self):
        self.finish_login(False)
        self.root.destroy()


def run_gui(browser, interval, *, auto_scan):
    root = tk.Tk()
    Dashboard(root, browser, interval, auto_scan)
    root.mainloop()
    return 0
