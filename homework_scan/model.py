from collections import defaultdict
from dataclasses import dataclass, field
from hashlib import sha256


@dataclass(frozen=True)
class Course:
    platform: str
    id: str
    name: str


@dataclass(frozen=True)
class Task:
    course: Course
    kind: str
    key: str
    title: str
    opens_at: str | None = None
    due_at: str | None = None
    due_precision: str | None = None
    status: str | None = None
    open_url: str | None = None
    open_scope: str | None = None

    def record(self):
        identity = f"{self.course.platform}\0{self.course.id}\0{self.kind}\0{self.key}"
        return {
            "id": sha256(identity.encode()).hexdigest()[:24],
            "platform": self.course.platform,
            "course_id": self.course.id,
            "course_name": self.course.name,
            "kind": self.kind,
            "title": self.title,
            "opens_at": self.opens_at,
            "due_at": self.due_at,
            "due_precision": self.due_precision,
            "status": self.status,
            "open_url": self.open_url,
            "open_scope": self.open_scope,
        }


@dataclass
class Scan:
    course_count: int = 0
    tasks: list[Task] = field(default_factory=list)
    issues: list[str] = field(default_factory=list)
    scanned_course_count: int = 0
    unopened_course_count: int = 0


class LoginRequired(Exception):
    pass


class PageChanged(Exception):
    pass


def normalize(text):
    return " ".join(text.split())


def sort_key(task):
    timestamp = task["due_at"] or task["opens_at"]
    if timestamp and len(timestamp) == 10:
        timestamp += "T23:59:59+08:00"
    return timestamp is None, timestamp or "", task["title"], task["id"]


def group_tasks(tasks):
    groups = defaultdict(list)
    for task in sorted(tasks, key=sort_key):
        groups[task["platform"], task["course_id"], task["course_name"]].append(task)
    return [
        {"platform": platform, "course_id": course_id, "course_name": name, "tasks": items}
        for (platform, course_id, name), items in groups.items()
    ]
