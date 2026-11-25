"""Store the last password login result without saving any session data."""

import json

from . import browser


def password_status_path(platform):
    return browser.STATE / "auth" / f"{platform}.password-status.json"


def get_password_status(platform):
    try:
        data = json.loads(password_status_path(platform).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if isinstance(data, dict) and isinstance(data.get("verified"), bool):
        return data["verified"]
    return None


def set_password_status(platform, verified):
    browser.write_json(password_status_path(platform), {"verified": verified})


def reset_password_status(platform):
    password_status_path(platform).unlink(missing_ok=True)
