import re
from collections import Counter
from dataclasses import replace
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit

from playwright.sync_api import Error

from .browser import LOGIN
from .dates import parse_times
from .model import Course, LoginRequired, PageChanged, Scan, Task, normalize

COURSE_LINK = 'a.color1[href*="/visit/stucoursemiddle"]:visible'
FOLDER = (
    '#fileList li[onclick^="intoFolder("]:visible, '
    '#fileList li [onclick^="intoFolder("]:visible, '
    '#fileList li a[href^="javascript:intoFolder("]:visible'
)
ROW = "li:has(.right-content .overHidden2)"
NEXT = "li.xl-nextPage:not(.xl-disabled):visible"
EXAM_REVIEW = re.compile(r"""viewExamAnswer\(\s*['\"](\d+)['\"]\s*,\s*['\"](\d+)['\"]\s*\)\s*;?""")
EXAM_ENTRY = re.compile(r"goTest\(([^()]*)\)\s*;?")
WORK_DETAIL = re.compile(r"/(?:mooc-ans/)?mooc2?/work/(?:view|preview|dowork)")
EXAM_DETAIL = "/exam-ans/exam/test/look"
SECTIONS = (
    ("作业", "assignment", "/work/list", "frame_content-zy"),
    ("考试", "exam", "/exam-list", "frame_content-ks"),
)


def is_chaoxing(url):
    parsed = urlsplit(url)
    return parsed.scheme == "https" and (parsed.hostname or "").endswith(".chaoxing.com")


def needs_login(url):
    return (urlsplit(url).hostname or "").startswith("passport")


def rendered_links(frame, issues, *, location):
    links = frame.locator(COURSE_LINK)
    empty = frame.locator("#nullCourse:visible")
    links.or_(empty).first.wait_for()
    cards = frame.locator("#courseList > li.course:visible")
    if cards.count() != links.count():
        issues.append(f"{location}页面有 {cards.count()} 张课程卡，识别到 {links.count()} 个入口")
    return links.evaluate_all(
        """links => links.map(link => [
            link.innerText,
            link.href,
            link.closest('li.course')?.querySelector('.not-open-tip')?.textContent.trim() || '',
        ])"""
    )


def course_links(page, issues):
    def directory():
        with page.expect_response(course_directory_response) as loaded:
            page.goto(LOGIN["chaoxing"][1], wait_until="domcontentloaded")
            if needs_login(page.url):
                raise LoginRequired
        response = loaded.value
        if not response.ok:
            raise PageChanged
        response.finished()
        result = page.frame_locator("iframe[name=frame_content]")
        result.get_by_text("我学的课", exact=True).first.wait_for()
        result.locator("#courselistArea #courseList, #courselistArea #nullCourse").first.wait_for(
            state="attached"
        )
        return result

    frame = directory()
    links = rendered_links(frame, issues, location="学习通课程目录")
    folders = frame.locator("#fileList > li:visible")
    folder_count = folders.count()
    for index in range(folder_count):
        if index:
            frame = directory()
            frame.locator("#fileList > li:visible").nth(index).wait_for()
        folder = frame.locator("#fileList > li:visible").nth(index)
        title = normalize(folder.inner_text())
        actions = frame.locator(FOLDER).and_(folder.or_(folder.locator("*")))
        try:
            if not actions.count():
                raise PageChanged
            old_list = frame.locator("#courseList").first.element_handle()
            with page.expect_response(course_directory_response) as loaded:
                actions.first.click()
            response = loaded.value
            if not response.ok:
                raise PageChanged
            response.request.frame.wait_for_function("old => !old.isConnected", arg=old_list)
            links.extend(rendered_links(frame, issues, location=f"学习通课程文件夹「{title}」"))
        except (Error, PageChanged):
            issues.append(f"学习通课程文件夹「{title}」无法核对")
    return links


def course_directory_response(response):
    return (
        is_chaoxing(response.url)
        and urlsplit(response.url).path.endswith("/visit/courselistdata")
        and response.request.resource_type == "xhr"
    )


def section_response(response, endpoint):
    return (
        is_chaoxing(response.url)
        and urlsplit(response.url).path.endswith(endpoint)
        and response.request.is_navigation_request()
    )


def observed_at(header):
    return parsedate_to_datetime(header) if header else datetime.now(timezone.utc)


def task_url(row, section_url, kind):
    if kind == "assignment" and row["work"]:
        try:
            address = urljoin(section_url, row["work"])
            path = urlsplit(address).path
        except ValueError:
            return None
        if is_chaoxing(address) and re.fullmatch(r"/(?:mooc-ans/)?mooc2?/work/task", path):
            return address
    if kind != "exam":
        return None

    course_id, class_id, cpi = row["examContext"]
    if not all(value.isdecimal() for value in (course_id, class_id, cpi)):
        return None
    query = {"courseId": course_id, "classId": class_id, "cpi": cpi}
    action = row["examAction"].strip()
    if match := EXAM_REVIEW.fullmatch(action):
        path = "/exam-ans/exam/test/look"
        query["examId"], query["examAnswerId"] = match.groups()
    elif match := EXAM_ENTRY.fullmatch(action):
        args = [arg.strip() for arg in match[1].split(",")]
        if len(args) != 8 or args[7].strip("'\"") not in ("0", "false"):
            return None
        exam_id = args[1].strip("'\"")
        if not exam_id.isdecimal() or args[5] not in ("false", "0", "true", "1"):
            return None
        path = "/exam-ans/exam/test/examcode/examnotes"
        query["examId"] = exam_id
        if args[5] in ("true", "1"):
            query["reset"] = "true"
    else:
        return None
    return urljoin(section_url, path + "?" + urlencode(query))


def list_tasks(page, course, label, kind, endpoint, frame_name):
    nav = page.locator("a").get_by_text(label, exact=True).first
    with page.expect_response(lambda response: section_response(response, endpoint)) as loaded:
        nav.click()
    response = loaded.value
    section_url = response.url
    frame = page.frame_locator(f'iframe[name="{frame_name}"]')
    rows = frame.locator(ROW)
    empty = frame.get_by_text(re.compile(rf"暂无{label}|没有{label}")).first
    tasks = []
    title_counts = Counter()
    page_numbers = set()
    while True:
        if response.status >= 400:
            raise PageChanged
        rows.first.or_(empty).first.wait_for()
        now = observed_at(response.header_value("date"))
        for row in rows.evaluate_all(
            """elements => elements.map(row => {
                const text = selector => row.querySelector(selector)?.innerText.trim() || '';
                const work = row.getAttribute('data');
                return {
                    title: text('.right-content .overHidden2'),
                    status: text('.right-content .status'),
                    time: text('.time'),
                    work,
                    examAction: (row.querySelector('[onclick^="viewExamAnswer("], [onclick^="goTest("]') || row)
                        .getAttribute('onclick') || '',
                    examContext: ['courseId', 'classId', 'cpi'].map(id => document.getElementById(id)?.value || ''),
                };
            })"""
        ):
            title = normalize(row["title"])
            if not title:
                continue
            title_counts[title] += 1
            direct_url = task_url(row, section_url, kind)
            work_id = parse_qs(urlsplit(direct_url).query).get("workId", [""])[0] if direct_url else ""
            key = work_id or f"{title}:{title_counts[title]}"
            opens_at, due_at, precision = parse_times(row["time"], now)
            tasks.append(Task(
                course, kind, key, title, opens_at, due_at, precision,
                normalize(row["status"]) or None, open_url=direct_url or section_url,
                open_scope="task" if direct_url else "section",
            ))
        next_page = frame.locator(NEXT).first
        if not next_page.is_visible():
            return tasks
        active = frame.locator("li.xl-active").first
        number = normalize(active.inner_text())
        if not number.isdigit() or number in page_numbers:
            raise PageChanged
        page_numbers.add(number)
        with page.expect_response(lambda res: section_response(res, endpoint)) as next_loaded:
            next_page.click()
        response = next_loaded.value
        active.filter(has_text=re.compile(rf"^{int(number) + 1}$")).wait_for()


def detail_html(request, task):
    url = task.open_url
    path = urlsplit(url).path
    if not is_chaoxing(url):
        return None
    if task.kind == "assignment" and re.fullmatch(r"/(?:mooc-ans/)?mooc2?/work/task", path):
        response = request.get(url, max_redirects=0, timeout=5000)
        try:
            target = urljoin(url, response.headers.get("location", ""))
            target_path = urlsplit(target).path
            if not (300 <= response.status < 400 and is_chaoxing(target)):
                return None
            if not WORK_DETAIL.fullmatch(target_path):
                return None
            url = target
        finally:
            response.dispose()
    elif task.kind != "exam" or path != EXAM_DETAIL:
        return None

    response = request.get(url, max_redirects=0, timeout=5000)
    try:
        if response.ok:
            return response.text(), observed_at(response.headers.get("date"))
        return None
    finally:
        response.dispose()


def fill_detail_times(context, tasks):
    pending = [
        (index, task) for index, task in enumerate(tasks)
        if task.open_scope == "task"
        and (not task.due_at or task.kind == "assignment" and task.due_precision == "estimated")
    ]
    if not pending:
        return
    page = context.new_page()
    try:
        page.route("**/*", lambda route: route.abort())
        for index, task in pending:
            try:
                document = detail_html(context.request, task)
                if not document:
                    continue
                html, observed = document
                labels = page.evaluate(
                    r"""({html, kind}) => {
                        const doc = new DOMParser().parseFromString(html, 'text/html');
                        const selector = kind === 'exam' ? '.result_gray_div p'
                            : '.infoHead p, .mark_title p, .mark_title span, .word_null p';
                        const texts = [...doc.querySelectorAll(selector)].map(node => node.textContent.trim());
                        const window = texts.find(text => /^作答时间\s*[:：]\s*\d/.test(text));
                        return window || texts.filter(text => /^(?:作业未开始|开始时间|截止时间|结束时间)/.test(text)).join('\n');
                    }""",
                    {"html": html, "kind": task.kind},
                )
            except (Error, ValueError):
                continue
            opens_at, due_at, precision = parse_times(normalize(labels), observed)
            if opens_at or due_at:
                tasks[index] = replace(
                    task, opens_at=opens_at or task.opens_at, due_at=due_at or task.due_at,
                    due_precision=precision or task.due_precision,
                )
    finally:
        page.close()


def scan(context):
    result = Scan()
    directory = context.new_page()
    try:
        links = course_links(directory, result.issues)
    finally:
        directory.close()
    seen = set()
    for name, url, status in links:
        if "已结束" in status or "已结课" in status:
            continue
        if not is_chaoxing(url):
            result.course_count += 1
            result.issues.append("课程列表包含无法确认的入口")
            continue
        query = parse_qs(urlsplit(url).query)
        course_id = query.get("courseid", [""])[0]
        class_id = query.get("clazzid", [""])[0]
        if not course_id or not class_id:
            result.course_count += 1
            result.issues.append(f"{normalize(name)} · 课程标识缺失")
            continue
        course = Course("chaoxing", f"{course_id}:{class_id}", normalize(name))
        if course.id in seen:
            continue
        seen.add(course.id)
        result.course_count += 1
        if re.search(r"未开始|未开课|未开放|即将", status):
            result.unopened_course_count += 1
            continue
        initial_issues = len(result.issues)
        page = context.new_page()
        try:
            page.goto(url, wait_until="domcontentloaded")
            if needs_login(page.url):
                raise LoginRequired
            for label, kind, endpoint, frame in SECTIONS:
                try:
                    result.tasks.extend(list_tasks(page, course, label, kind, endpoint, frame))
                except (Error, PageChanged):
                    result.issues.append(f"{course.name} · {label}列表无法确认")
        except Error:
            result.issues.append(f"{course.name} · 课程学习页无法加载")
        finally:
            page.close()
        if len(result.issues) == initial_issues:
            result.scanned_course_count += 1
    fill_detail_times(context, result.tasks)
    return result
