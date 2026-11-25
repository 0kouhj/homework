import json
from datetime import datetime
from zoneinfo import ZoneInfo

from .browser import STATE, write_json
from .model import group_tasks

TIMEZONE = ZoneInfo("Asia/Shanghai")
OUTPUT = STATE / "tasks.json"


def now():
    return datetime.now(TIMEZONE).isoformat(timespec="seconds")


def read_snapshot(path=OUTPUT):
    if not path.exists():
        return {"schema_version": 1, "updated_at": None, "platforms": {}, "courses": []}
    return json.loads(path.read_text(encoding="utf-8"))


def update_snapshot(snapshot, platform, status, result, started_at, path=OUTPUT):
    tasks = {
        task["id"]: task
        for group in snapshot["courses"]
        for task in group["tasks"]
        if task["platform"] != platform or status != "ok"
    }
    fresh = {record["id"]: record for record in (task.record() for task in result.tasks)}
    tasks.update(fresh)
    finished_at = now()
    last_success = snapshot["platforms"].get(platform, {}).get("last_success_at")
    cached_count = sum(
        task["platform"] == platform and task["id"] not in fresh
        for task in tasks.values()
    )
    snapshot["platforms"][platform] = {
        "status": status,
        "last_attempt_at": started_at,
        "last_success_at": finished_at if status == "ok" else last_success,
        "course_count": result.course_count,
        "scanned_course_count": result.scanned_course_count,
        "unopened_course_count": result.unopened_course_count,
        "task_count": len(fresh),
        "cached_task_count": cached_count,
        "issues": result.issues,
    }
    snapshot["updated_at"] = finished_at
    snapshot["courses"] = group_tasks(tasks.values())
    write_json(path, snapshot)
    return snapshot["platforms"][platform]
