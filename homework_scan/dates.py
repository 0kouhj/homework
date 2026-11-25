import re
from datetime import date, datetime, timedelta
from math import ceil
from zoneinfo import ZoneInfo

CHINA = ZoneInfo("Asia/Shanghai")
DATE = re.compile(
    r"(?<!\d)(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?"
    r"(?:(?:[ T]+)(\d{1,2})[:：](\d{2})(?:[:：](\d{2}))?)?"
)
START = re.compile(r"(?:开始|开放)(?:时间)?[：:\s]*$")
END = re.compile(r"(?:截止|截至|结束)(?:时间)?[：:\s]*$")
RANGE = re.compile(r"\s*(?:至|到|~|～|—|–|-)\s*")
ANSWER_WINDOW = re.compile(r"作答时间\s*[:：]\s*(.*?)\s*至\s*(.*?)\s*")
SHORT_DATE = re.compile(r"\d{1,2}-\d{1,2}[ T]+\d{1,2}:\d{2}(?::\d{2})?")
SHORT_RANGE_END = re.compile(
    r"\s*(?:至|到|~|～|—|–|-)\s*((\d{1,2})-(\d{1,2})[ T]+\d{1,2}:\d{2}(?::\d{2})?)\s*"
)
COUNTDOWN = re.compile(
    r"剩余\s*(?:(\d+)\s*天\s*)?(?:(\d+)\s*(?:小时|时)\s*)?(?:(\d+)\s*(?:分钟|分))?"
)


def parse_date(match):
    year, month, day, hour, minute, second = match.groups()
    try:
        if hour is None:
            return date(int(year), int(month), int(day)).isoformat(), "date"
        timestamp = datetime(
            int(year), int(month), int(day), int(hour), int(minute), int(second or 0),
            tzinfo=CHINA,
        )
        return timestamp.isoformat(timespec="seconds"), "exact"
    except ValueError:
        return None, None


def parse_times(text, observed_at=None):
    # 学习通的作答区间省略了与页面时间同年的年份。
    if observed_at and observed_at.utcoffset() is not None and (window := ANSWER_WINDOW.fullmatch(text)):
        year = observed_at.astimezone(CHINA).year
        start, end = [
            DATE.fullmatch(f"{year}-{part}" if SHORT_DATE.fullmatch(part) else part)
            for part in window.groups()
        ]
        if start and end:
            opens_at, _ = parse_date(start)
            due_at, precision = parse_date(end)
            if opens_at and due_at and opens_at <= due_at:
                return opens_at, due_at, precision
    matches = list(DATE.finditer(text))
    opens_at = due_at = precision = None
    for index, match in enumerate(matches):
        value, format_ = parse_date(match)
        if value is None:
            continue
        prefix = text[:match.start()]
        if END.search(prefix):
            due_at, precision = value, format_
        elif START.search(prefix):
            opens_at = value
        elif len(matches) == 1 and not prefix.strip():
            due_at, precision = value, format_
        if index:
            previous = matches[index - 1]
            if RANGE.fullmatch(text[previous.end():match.start()]):
                opens_at = parse_date(previous)[0]
                due_at, precision = value, format_
        elif len(matches) == 1 and (end := SHORT_RANGE_END.fullmatch(text[match.end():])):
            year, month, day = map(int, match.groups()[:3])
            end_month, end_day = map(int, end.groups()[1:])
            year += (end_month, end_day) < (month, day)
            if full_end := DATE.fullmatch(f"{year:04d}-{end[1]}"):
                end_at, end_precision = parse_date(full_end)
                if end_at and end_at >= value:
                    opens_at, due_at, precision = value, end_at, end_precision
    if not due_at and observed_at and observed_at.utcoffset() is not None:
        countdown = COUNTDOWN.search(text)
        if countdown and any(value is not None for value in countdown.groups()):
            days, hours, minutes = (int(value or 0) for value in countdown.groups())
            end = observed_at.astimezone(CHINA) + timedelta(days=days, hours=hours, minutes=minutes)
            if end.second or end.microsecond:
                end += timedelta(minutes=1)
            due_at, precision = end.replace(second=0, microsecond=0).isoformat(), "estimated"
    return opens_at, due_at, precision


def remaining_time(due_at, as_of):
    if not due_at:
        return {"state": "unknown", "seconds": None, "days": None, "text": "截止时间未知"}
    local_now = as_of.astimezone(CHINA)
    if len(due_at) == 10:
        days = (date.fromisoformat(due_at) - local_now.date()).days
        if days < 0:
            return {"state": "elapsed", "seconds": None, "days": days, "text": "已截止"}
        if days == 0:
            return {"state": "today", "seconds": None, "days": 0, "text": "今天截止"}
        return {"state": "remaining", "seconds": None, "days": days, "text": f"剩余 {days} 天"}
    deadline = datetime.fromisoformat(due_at)
    seconds = (deadline - local_now).total_seconds()
    if seconds <= 0:
        return {"state": "elapsed", "seconds": ceil(seconds), "days": None, "text": "已截止"}
    minutes = ceil(seconds / 60)
    days, remainder = divmod(minutes, 24 * 60)
    hours, minutes = divmod(remainder, 60)
    parts = [f"{value} {unit}" for value, unit in ((days, "天"), (hours, "小时"), (minutes, "分钟")) if value]
    return {
        "state": "remaining",
        "seconds": ceil(seconds),
        "days": None,
        "text": "剩余 " + " ".join(parts),
    }
