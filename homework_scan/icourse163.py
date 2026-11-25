import re
from collections import Counter, defaultdict
from urllib.parse import parse_qs, parse_qsl, urlencode, urlsplit

from playwright.sync_api import Error

from .dates import parse_times
from .model import Course, LoginRequired, PageChanged, Scan, Task, normalize

HOME = "https://www.icourse163.org/home.htm"
CARD = "a.j-course-card-box:visible"
MODES = (("MOOC", "/home/course"), ("SPOC", "/home/spocCourse"))
SECTIONS = (
    ("测验与作业", "/learn/testlist", "/assessments"),
    ("考试", "/learn/examlist", "/exams"),
)
ROW = ".u-quizHwListItem:has(.j-titleBox .j-name):visible"
SMART_WORK = "button.learn-assessment-head[data-assessment-toggle]:visible"
SMART_EXAM = ".learn-exam-card-title:visible"
LEGACY_TERM = "/web/j/courseBean.getLastLearnedMocTermDto.rpc"
LOGIN_TEXT = re.compile(r"^登录(?:\s*\|\s*注册)?$")
EMPTY = re.compile(r"暂无(?:测验|考试|作业|发布|内容)|未发布(?:测验|考试|作业)|还未开放|没有(?:测验|考试|作业)")
UNOPENED = re.compile(r"即将(?:开始|开课)|尚未(?:开始|开课|开放)|未(?:开始|开课|开放)|[天日]后开课")


def needs_login(page):
    return page.get_by_text(LOGIN_TEXT).filter(visible=True).first.is_visible()


def course_cards(page, mode, fragment):
    page.goto(HOME + "#" + fragment, wait_until="domcontentloaded")
    header = page.get_by_text(re.compile(rf"^{mode}\s*课程\s*[（(]\d+[）)]$")).first
    try:
        header.filter(visible=True).or_(
            page.get_by_text(LOGIN_TEXT).filter(visible=True)
        ).first.wait_for()
    except Error as exc:
        raise PageChanged(f"{mode} 课程页未加载") from exc
    if needs_login(page):
        raise LoginRequired
    header.wait_for()
    total = int(re.search(r"\d+", header.inner_text()).group())
    cards = page.locator(CARD)
    if total:
        try:
            cards.first.wait_for()
        except Error:
            pass
    rows = []
    matches = Counter()
    for card in cards.all():
        name = normalize(card.locator(".title > .text").first.inner_text())
        status = normalize(" / ".join(card.locator(".course-status").all_inner_texts()))
        if name:
            matching = cards.filter(
                has=page.locator(".title > .text").filter(has_text=re.compile(rf"^\s*{re.escape(name)}\s*$"))
            )
            if status:
                matching = matching.filter(
                    has=page.locator(".course-status").filter(has_text=re.compile(rf"^\s*{re.escape(status)}\s*$"))
                )
            index = matches[name, status]
            matches[name, status] += 1
            rows.append((matching.nth(index), name, status))
    warning = None
    if len(rows) != total:
        warning = f"{mode} 课程列表覆盖不完整：页面统计 {total} 门，已读取 {len(rows)} 门"
    return [row for row in rows if "已结束" not in row[2] and "已结课" not in row[2]], warning


def legacy_rows(rows):
    return rows.evaluate_all(
        """elements => elements.map(row => {
            const header = row.querySelector('.j-titleBox');
            const text = (node, selector) => node.querySelector(selector)?.textContent.trim() || '';
            return {
                title: text(header, '.j-name'),
                button: text(header, '.j-quizBtn'),
                deadline: text(header, '.j-submitTime') || text(row, '.j-submitTime'),
                start: text(row, '.j-startTime'),
                status: text(header, '.j-validScore'),
                chapter: row.closest('.m-chapterQuizHwItem')?.querySelector('.j-titleName')?.innerText || '',
                rowId: row.id,
            };
        })"""
    )


def smart_rows(rows, is_exam):
    return rows.evaluate_all(
        """(elements, isExam) => elements.map(row => {
            const text = selector => row.querySelector(selector)?.textContent.trim() || '';
            const panelId = row.closest('button')?.getAttribute('aria-controls') || '';
            const panel = document.getElementById(panelId);
            const action = panel?.querySelector(isExam ? '.learn-exam-part-action[href]' : 'a[data-assessment-link][href]');
            return {
                title: text(isExam ? 'strong' : '.learn-assessment-title'),
                key: isExam ? '' : panelId,
                button: panelId.includes('-hw-') || panelId.includes('-homework-') ? '作业' : '',
                deadline: text(isExam ? '.learn-exam-card-summary' : '.learn-assessment-deadline'),
                start: '',
                status: '',
                url: action?.href || '',
            };
        })""",
        is_exam,
    )


def legacy_links(responses, section_url):
    """Read IDs from the course payload the list already loaded."""
    links = defaultdict(set)
    term_id = parse_qs(section_url.query).get("tid", [""])[0]
    terms = {}
    for response in reversed(responses):
        try:
            term = response.json()["result"]["mocTermDto"]
            terms.setdefault(str(term["id"]), term)
        except (Error, KeyError, TypeError, ValueError):
            continue
    if term_id not in terms:
        return links

    def link(route, **params):
        query = urlencode(params)
        return section_url._replace(fragment=f"/learn/{route}?{query}").geturl()

    for loaded_id, term in terms.items():
        for chapter in term.get("chapters") or []:
            chapter_name = normalize(chapter.get("name") or "")
            for field, kind, route in (("homeworks", "assignment", "hw"), ("quizs", "quiz", "quiz")):
                for item in chapter.get(field) or []:
                    task_id = str(item.get("contentId"))
                    title = normalize(item.get("name") or "")
                    if not (task_id.isdecimal() and title):
                        continue
                    address = link(route, id=task_id)
                    # A SPOC list can include chapters from other terms it loads.
                    if chapter_name:
                        links[kind, title, chapter_name].add(address)
                    if loaded_id == term_id:
                        links[kind, title].add(address)

        for exam in term.get("exams") or []:
            exam_id = str(exam.get("id"))
            chapter_id = str(exam.get("chapterId"))
            if not (exam_id.isdecimal() and chapter_id.isdecimal()):
                continue
            objective = str(exam.get("objectTestId"))
            subjective = str(exam.get("subjectTestId"))
            if objective.isdecimal() and int(objective) > 0:
                address = link("examObject", eid=exam_id, id=objective, examType=exam.get("examType") or 0)
            elif subjective.isdecimal() and int(subjective) > 0:
                address = link("examSubjective", eid=exam_id, id=subjective)
            else:
                continue
            links["exam", chapter_id].add(address)
    return links


def legacy_task_url(links, row, kind, title):
    if kind == "exam":
        candidates = links.get((kind, row["rowId"]), ())
    else:
        candidates = links.get((kind, title, normalize(row["chapter"]))) or links.get((kind, title), ())
    return next(iter(candidates)) if len(candidates) == 1 else None


def wait_for_task_rows(page, selector):
    # The legacy page can render one chapter first and then append other chapters.
    stable = page.evaluate(
        """async selector => {
            const settle = 750;
            const limit = performance.now() + 5000;
            const signature = () => JSON.stringify([...document.querySelectorAll(selector)]
                .filter(row => row.getClientRects().length)
                .map(row => row.textContent.trim()));
            let previous = signature();
            let changedAt = performance.now();
            while (performance.now() < limit) {
                await new Promise(resolve => setTimeout(resolve, 125));
                const current = signature();
                if (current !== previous) {
                    previous = current;
                    changedAt = performance.now();
                } else if (performance.now() - changedAt >= settle) {
                    return true;
                }
            }
            return false;
        }""",
        selector.removesuffix(":visible"),
    )
    if not stable:
        raise PageChanged


def load_task_section(page, label, route, smart_suffix, *, smart):
    nav = page.locator("a").get_by_text(label, exact=True).first
    nav.wait_for()
    # These exact menu anchors change sections while leaving attempts untouched.
    nav.dispatch_event("click")
    page.wait_for_url(
        lambda url: urlsplit(url).path.endswith(smart_suffix)
        if smart else urlsplit(url).fragment == route,
        timeout=10000,
    )
    section_url = urlsplit(page.url)
    term_query = urlencode([(key, value) for key, value in parse_qsl(section_url.query) if key == "tid"])
    section_url = section_url._replace(query=term_query)
    is_exam = label == "考试"
    selector = (SMART_EXAM if is_exam else SMART_WORK) if smart else ROW
    rows = page.locator(selector)
    empty = page.get_by_text(EMPTY).filter(visible=True)
    rows.or_(empty).first.wait_for()
    wait_for_task_rows(page, selector)
    data = smart_rows(rows, is_exam) if smart else legacy_rows(rows)
    return section_url, data


def list_tasks(page, course, label, route, smart_suffix, *, smart):
    responses = []

    def collect_term(response):
        url = urlsplit(response.url)
        if response.ok and url.hostname == "www.icourse163.org" and url.path == LEGACY_TERM:
            responses.append(response)

    if not smart:
        page.on("response", collect_term)
    try:
        section_url, data = load_task_section(page, label, route, smart_suffix, smart=smart)
    finally:
        if not smart:
            page.remove_listener("response", collect_term)
    links = legacy_links(responses, section_url) if responses else {}
    tasks = []
    titles = Counter()
    for row in data:
        title = normalize(row["title"])
        if not title:
            continue
        if label == "考试":
            kind = "exam"
        else:
            kind = "assignment" if "作业" in row["button"] or "作业" in title else "quiz"
        titles[kind, title] += 1
        key = row.get("key") or f"{title}:{titles[kind, title]}"
        opens_at, due_at, precision = parse_times(row["deadline"])
        if row["start"] and not opens_at:
            opens_at = parse_times("开始时间：" + row["start"])[0]
        direct_url = row.get("url") if smart else legacy_task_url(links, row, kind, title)
        if direct_url:
            url = urlsplit(direct_url)
            if url.scheme != "https" or url.netloc != "www.icourse163.org":
                direct_url = None
        tasks.append(Task(
            course, kind, key, title, opens_at, due_at, precision,
            normalize(row["status"]) or None, open_url=direct_url or section_url.geturl(),
            open_scope="task" if direct_url else "section",
        ))
    return tasks


def scan(context):
    result = Scan()
    for mode, fragment in MODES:
        directory = context.new_page()
        try:
            cards, warning = course_cards(directory, mode, fragment)
        except (Error, PageChanged) as exc:
            result.issues.append(f"{mode} 课程列表无法确认（{type(exc).__name__}）")
            directory.close()
            continue
        except LoginRequired:
            directory.close()
            raise
        if warning:
            result.issues.append(warning)
        result.course_count += len(cards)
        seen = set()
        for card, name, status in cards:
            if UNOPENED.search(status):
                result.unopened_course_count += 1
                continue
            initial_issues = len(result.issues)
            study = None
            try:
                with directory.expect_popup() as popup:
                    card.locator(".title > .text").first.click()
                study = popup.value
                study.wait_for_load_state("domcontentloaded")
                url = urlsplit(study.url)
                legacy = re.fullmatch(r"(/(?:spoc/)?learn/[^/]+)", url.path)
                smart = re.fullmatch(r"/mooc(/learn/[^/]+)/(?:content|assessments|exams)", url.path)
                if url.hostname != "www.icourse163.org" or not (legacy or smart):
                    if needs_login(study):
                        raise LoginRequired
                    raise PageChanged
                if not study.title().startswith(name + "_"):
                    study.get_by_text(name, exact=True).filter(visible=True).first.wait_for()
                term = parse_qs(url.query).get("tid", [""])[0]
                course = Course("icourse163", f"{(legacy or smart)[1]}:{term}", name)
                if course.id in seen:
                    result.issues.append(f"{name} · 多张课程卡指向同一课程，无法确认覆盖")
                    continue
                seen.add(course.id)
                for label, section, smart_suffix in SECTIONS:
                    try:
                        result.tasks.extend(
                            list_tasks(study, course, label, section, smart_suffix, smart=bool(smart))
                        )
                        if label == "考试" and not smart:
                            new_exam = study.get_by_text("新版考试", exact=True).filter(visible=True)
                            no_new_exam = study.get_by_text("暂无新版考试", exact=True).filter(visible=True)
                            if new_exam.first.is_visible() and not no_new_exam.first.is_visible():
                                try:
                                    no_new_exam.first.wait_for(timeout=3000)
                                except Error:
                                    result.issues.append(f"{name} · 新版考试内容暂时无法确认")
                    except (Error, PageChanged):
                        result.issues.append(f"{name} · {label}列表无法确认")
            except (Error, PageChanged):
                result.issues.append(f"{name} · 课程学习页无法确认")
            finally:
                if study:
                    study.close()
            if len(result.issues) == initial_issues:
                result.scanned_course_count += 1
        directory.close()
    return result
