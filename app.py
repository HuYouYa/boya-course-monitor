"""博雅课程监控器。

启动：python -m uvicorn app:app --host 127.0.0.1 --port 8787
首次使用需要安装浏览器：python -m playwright install chromium
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import smtplib
import sqlite3
import threading
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from email.headerregistry import Address
from email.message import EmailMessage
from email.utils import formataddr
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from starlette.applications import Starlette
from starlette.middleware.cors import CORSMiddleware
from starlette.responses import FileResponse, JSONResponse, PlainTextResponse
from starlette.routing import Mount, Route
from starlette.staticfiles import StaticFiles

ROOT = Path(__file__).parent
DATA_DIR = ROOT / "data"
DATA_DIR.mkdir(exist_ok=True)
DB_PATH = Path(os.getenv("BOYA_DB", str(DATA_DIR / "boya.sqlite3")))
SITE_URL = "https://bykc.buaa.edu.cn/#/tabs/%E9%80%89%E8%AF%BE/course-home"
COURSE_ROUTE = "https://bykc.buaa.edu.cn/system/course-select"
POLL_INTERVAL = max(60, int(os.getenv("BOYA_POLL_INTERVAL", "300")))
logger = logging.getLogger("boya-monitor")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")


def _connection() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


class SecretVault:
    """用本地随机密钥加密站点密码和 SMTP 密码，避免明文写入 SQLite。"""

    def __init__(self) -> None:
        key_path = ROOT / ".secret"
        if key_path.exists():
            self.key = key_path.read_bytes()
        else:
            self.key = secrets.token_bytes(32)
            key_path.write_bytes(self.key)

    def encrypt(self, value: str | None) -> str | None:
        if not value:
            return None
        nonce = secrets.token_bytes(16)
        stream = self._stream(nonce, len(value.encode()))
        raw = bytes(a ^ b for a, b in zip(value.encode(), stream))
        mac = hmac.new(self.key, nonce + raw, hashlib.sha256).digest()
        return base64.urlsafe_b64encode(nonce + mac + raw).decode()

    def decrypt(self, value: str | None) -> str:
        if not value:
            return ""
        try:
            packed = base64.urlsafe_b64decode(value.encode())
            nonce, mac, raw = packed[:16], packed[16:48], packed[48:]
            if not hmac.compare_digest(mac, hmac.new(self.key, nonce + raw, hashlib.sha256).digest()):
                return ""
            return bytes(a ^ b for a, b in zip(raw, self._stream(nonce, len(raw)))).decode()
        except (ValueError, UnicodeDecodeError):
            return ""

    def _stream(self, nonce: bytes, length: int) -> bytes:
        out = b""
        counter = 0
        while len(out) < length:
            out += hmac.new(self.key, nonce + counter.to_bytes(4, "big"), hashlib.sha256).digest()
            counter += 1
        return out[:length]


VAULT = SecretVault()


DEFAULT_CONFIG: dict[str, Any] = {
    "username": "",
    "delivery_mode": "offline",
    "campus": "学院路",
    "free_hours": {str(day): list(range(24)) for day in range(7)},
    "free_ranges": {str(day): "00:00-24:00" for day in range(7)},
    "notify_channel": "email",
    "poll_interval": POLL_INTERVAL,
    "smtp_host": "",
    "smtp_port": 465,
    "smtp_username": "",
    "smtp_from": "",
    "smtp_to": "",
    "smtp_tls": True,
    "qq_to": "",
}


class ConfigStore:
    def __init__(self) -> None:
        with _connection() as c:
            c.execute("CREATE TABLE IF NOT EXISTS config (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
            c.execute("CREATE TABLE IF NOT EXISTS courses (id TEXT PRIMARY KEY, payload TEXT NOT NULL, first_seen TEXT NOT NULL, notified INTEGER DEFAULT 0)")
            c.execute("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL)")
            if c.execute("SELECT 1 FROM config WHERE id=1").fetchone() is None:
                c.execute("INSERT INTO config(id,payload) VALUES(1,?)", (json.dumps(DEFAULT_CONFIG, ensure_ascii=False),))

    def get(self) -> dict[str, Any]:
        with _connection() as c:
            raw = json.loads(c.execute("SELECT payload FROM config WHERE id=1").fetchone()[0])
        raw.setdefault("free_hours", DEFAULT_CONFIG["free_hours"])
        raw.setdefault("free_ranges", DEFAULT_CONFIG["free_ranges"])
        raw.setdefault("campus", "学院路")
        raw.setdefault("delivery_mode", "offline")
        return raw

    def save(self, config: dict[str, Any]) -> None:
        with _connection() as c:
            c.execute("UPDATE config SET payload=? WHERE id=1", (json.dumps(config, ensure_ascii=False),))

    def save_course(self, course: dict[str, Any], notified: bool = False) -> None:
        with _connection() as c:
            c.execute(
                "INSERT INTO courses(id,payload,first_seen,notified) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload",
                (course["id"], json.dumps(course, ensure_ascii=False), datetime.now().isoformat(timespec="seconds"), int(notified)),
            )

    def get_course(self, course_id: str) -> dict[str, Any] | None:
        with _connection() as c:
            row = c.execute("SELECT payload FROM courses WHERE id=?", (course_id,)).fetchone()
        return json.loads(row[0]) if row else None

    def mark_notified(self, course_id: str) -> None:
        with _connection() as c:
            c.execute("UPDATE courses SET notified=1 WHERE id=?", (course_id,))

    def was_notified(self, course_id: str, course: dict[str, Any] | None = None) -> bool:
        with _connection() as c:
            row = c.execute("SELECT notified FROM courses WHERE id=?", (course_id,)).fetchone()
            if row and row[0]:
                return True
            # 页面解析规则升级后，旧记录的 id 可能变化；用课程业务字段避免重复发信。
            target = json.loads(c.execute("SELECT payload FROM courses WHERE id=?", (course_id,)).fetchone()[0]) if row else course
            if target:
                rows = c.execute("SELECT payload FROM courses WHERE notified=1").fetchall()
                for item in rows:
                    old = json.loads(item[0])
                    if (old.get("name"), old.get("start"), old.get("end"), old.get("select_start")) == (target.get("name"), target.get("start"), target.get("end"), target.get("select_start")):
                        return True
        return False

    def courses(self, limit: int = 100) -> list[dict[str, Any]]:
        with _connection() as c:
            rows = c.execute("SELECT payload FROM courses ORDER BY first_seen DESC LIMIT ?", (limit,)).fetchall()
        result, seen = [], set()
        for row in rows:
            course = json.loads(row[0])
            key = (course.get("name"), course.get("start"), course.get("end"), course.get("select_start"))
            if key in seen:
                continue
            seen.add(key); result.append(course)
        return result

    def event(self, level: str, message: str) -> None:
        with _connection() as c:
            c.execute("INSERT INTO events(created_at,level,message) VALUES(?,?,?)", (datetime.now().isoformat(timespec="seconds"), level, message))

    def events(self, limit: int = 50) -> list[dict[str, Any]]:
        with _connection() as c:
            rows = c.execute("SELECT created_at,level,message FROM events ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return [dict(row) for row in rows]


STORE = ConfigStore()


def parse_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    value = value.strip().replace("T", " ").replace("年", "-").replace("月", "-").replace("日", "")
    value = value.replace("/", "-")
    value = re.sub(r"[（(].*?[）)]", "", value)
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d", "%Y.%m.%d %H:%M", "%Y.%m.%d"):
        try:
            return datetime.strptime(value, fmt)
        except ValueError:
            continue
    match = re.search(r"(20\d{2})[-.](\d{1,2})[-.](\d{1,2}).*?(\d{1,2})[:：](\d{2})", value)
    return datetime(*map(int, match.groups())) if match else None


def _date_pair(text: str) -> tuple[datetime | None, datetime | None]:
    dates = re.findall(r"20\d{2}[年./-]\d{1,2}[月./-]\d{1,2}(?:日)?(?:\s*\d{1,2}[:：]\d{2})?", text)
    if len(dates) < 2:
        dates = re.findall(r"20\d{2}[-/]\d{1,2}[-/]\d{1,2}(?:\s+\d{1,2}[:：]\d{2})?", text)
    return (parse_datetime(dates[0]) if dates else None, parse_datetime(dates[1]) if len(dates) > 1 else None)


def normalize_course(raw: dict[str, Any]) -> dict[str, Any]:
    """将 API/页面字段统一成通知和过滤使用的格式。"""
    def names(value: Any) -> list[str]:
        if isinstance(value, list):
            result = []
            for x in value:
                if x is None:
                    continue
                result.append(str(x.get("name") or x.get("kindName") or x) if isinstance(x, dict) else str(x))
            return [x for x in result if x and x != "None"]
        if isinstance(value, dict):
            return [str(value.get("name") or value.get("kindName") or "")]
        return [x.strip() for x in re.split(r"[,，、/|]", str(value or "")) if x.strip()]

    start = raw.get("start") or raw.get("courseStartDate")
    end = raw.get("end") or raw.get("courseEndDate")
    select_start = raw.get("select_start") or raw.get("courseSelectStartDate")
    select_end = raw.get("select_end") or raw.get("courseSelectEndDate")
    campus_values = names(raw.get("campus") or raw.get("courseCampusList") or raw.get("courseCampus"))
    if any("全部" in value or "不限" in value for value in campus_values):
        campus_values = []
    current_count = raw.get("current_count") or raw.get("courseCurrentCount")
    max_count = raw.get("max_count") or raw.get("courseMaxCount")
    if isinstance(current_count, str) and "/" in current_count:
        current_count, max_count = (current_count.split("/", 1) + [max_count or ""])[:2]
    try:
        current_count = int(current_count) if current_count not in (None, "") else None
    except (TypeError, ValueError):
        current_count = None
    try:
        max_count = int(max_count) if max_count not in (None, "") else None
    except (TypeError, ValueError):
        max_count = None
    identity = raw.get("id") or "|".join(str(raw.get(key) or "") for key in ("name", "courseName", "start", "end", "select_start", "select_end", "position", "coursePosition"))
    course = {
        "id": hashlib.sha1(identity.encode()).hexdigest()[:16],
        "name": str(raw.get("name") or raw.get("courseName") or "未命名课程"),
        "category": " / ".join(names(raw.get("category") or [raw.get("courseNewKind1"), raw.get("courseNewKind2"), raw.get("courseNewKind3")])),
        "campus": campus_values,
        "position": str(raw.get("position") or raw.get("coursePosition") or ""),
        "teacher": str(raw.get("teacher") or raw.get("courseTeacher") or ""),
        "current_count": current_count,
        "max_count": max_count,
        "start": parse_datetime(str(start)) if start else None,
        "end": parse_datetime(str(end)) if end else None,
        "select_start": parse_datetime(str(select_start)) if select_start else None,
        "select_end": parse_datetime(str(select_end)) if select_end else None,
        "status": str(raw.get("status") or ""),
        "raw": raw,
    }
    for field in ("start", "end", "select_start", "select_end"):
        if course[field]:
            course[field] = course[field].isoformat(timespec="minutes")
    return course


def parse_course_rows(rows: list[list[str]]) -> list[dict[str, Any]]:
    """解析博雅页面的课程表行，列顺序来自真实页面模板。"""
    result = []
    for cells in rows:
        cells = [re.sub(r"\s+", " ", c).strip() for c in cells]
        if len(cells) < 7 or not cells[1]:
            continue
        activity_start, activity_end = _date_pair(cells[4])
        select_start, select_end = _date_pair(cells[6])
        campus_text = cells[5]
        campus_match = re.search(r"校区[:：]\s*([^\n]+)", campus_text)
        campus_value = campus_match.group(1).strip() if campus_match else campus_text.replace("校园：", "").replace("校园:", "").strip()
        raw = {
            "name": cells[1], "category": cells[2], "position": cells[3],
            "campus": campus_value,
            "start": activity_start.isoformat(sep=" ", timespec="minutes") if activity_start else None,
            "end": activity_end.isoformat(sep=" ", timespec="minutes") if activity_end else None,
            "select_start": select_start.isoformat(sep=" ", timespec="minutes") if select_start else None,
            "select_end": select_end.isoformat(sep=" ", timespec="minutes") if select_end else None,
            "status": cells[0], "teacher": cells[3], "source_cells": cells,
        }
        if len(cells) > 8:
            raw["current_count"] = cells[8]
        result.append(normalize_course(raw))
    return result


def course_fingerprint(course: dict[str, Any]) -> str:
    """只对会影响用户判断的课程字段做指纹，避免页面 HTML 细节导致重复通知。"""
    tracked = {
        key: course.get(key)
        for key in ("name", "category", "campus", "position", "teacher", "start", "end", "select_start", "select_end", "status", "current_count", "max_count")
    }
    return hashlib.sha256(json.dumps(tracked, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def _hours_between(start: datetime, end: datetime) -> list[tuple[int, int]]:
    """返回课程覆盖的每个 (星期, 小时)，end 采用半开区间。"""
    if end <= start:
        end = start + timedelta(hours=1)
    current = start.replace(minute=0, second=0, microsecond=0)
    output: list[tuple[int, int]] = []
    while current < end:
        output.append((current.weekday(), current.hour))
        current += timedelta(hours=1)
        if len(output) > 24 * 370:
            break
    return output


def fits_free_hours(course: dict[str, Any], free_hours: dict[str, list[int]]) -> bool:
    start = parse_datetime(course.get("start"))
    end = parse_datetime(course.get("end"))
    if not start:
        return False
    end = end or (start + timedelta(hours=1))
    allowed = {str(k): {int(h) for h in v} for k, v in (free_hours or {}).items()}
    return all(hour in allowed.get(str(day), set()) for day, hour in _hours_between(start, end))


def campus_matches(course: dict[str, Any], campus: str, delivery_mode: str = "offline") -> bool:
    """线上是上课形式，学院路/沙河是线下校区，二者不是同一层级。"""
    if delivery_mode not in {"offline", "online"}:
        delivery_mode = "offline"
    campus_values = set(course.get("campus", []))
    location = f"{' '.join(course.get('campus', []))} {course.get('position', '')}"
    online_aliases = {"线上", "在线", "网络", "网课", "远程"}
    is_online = any(alias in location for alias in online_aliases)
    location_has_xueyuan = "学院路" in location or bool(re.search(r"\bJ\d+[-—]?\d*", location, re.I))
    location_has_shahe = "沙河" in location or bool(re.search(r"\bSH\d+[-—]?\d*", location, re.I))
    if delivery_mode == "online":
        return is_online or not location.strip()
    if is_online:
        return False
    if not campus:
        return True
    aliases = {"学院路": {"学院路", "学院路校区"}, "沙河": {"沙河", "沙河校区"}, "线上": {"线上", "在线", "网络"}}
    wanted = aliases.get(campus, {campus})
    # 课程表的“校区”可能显示“全部校区”，此时以地点字段中的校区为准。
    if location_has_xueyuan or location_has_shahe:
        return (campus == "学院路" and location_has_xueyuan) or (campus == "沙河" and location_has_shahe)
    return not campus_values or bool(wanted.intersection(campus_values))


def smtp_identity(config: dict[str, Any]) -> tuple[str, str | None]:
    """允许“发件人”填写显示名，同时补全常见邮箱服务的登录地址。"""
    username = str(config.get("smtp_username") or "").strip()
    host = str(config.get("smtp_host") or "").lower()
    if "@" not in username:
        domains = {"smtp.163.com": "163.com", "smtp.qq.com": "qq.com", "smtp.gmail.com": "gmail.com"}
        if host in domains:
            username = f"{username}@{domains[host]}"
    sender = str(config.get("smtp_from") or "").strip()
    if "@" in sender:
        return username, sender
    return username, (formataddr((sender, username)) if sender else username)


class Notifier:
    def send(self, course: dict[str, Any], config: dict[str, Any]) -> None:
        channel = config.get("notify_channel", "email")
        target = config.get("qq_to") if channel == "qq" else config.get("smtp_to")
        if not target or not config.get("smtp_host"):
            raise ValueError("请先配置通知 SMTP 主机和收件地址")
        subject = f"[博雅课程提醒] {course['name']}"
        body = "\n".join([
            f"课程：{course['name']}", f"开课时间：{course.get('start') or '待公布'} - {course.get('end') or '待公布'}",
            f"预选时间：{course.get('select_start') or '待公布'} - {course.get('select_end') or '待公布'}",
            f"课程分类：{course.get('category') or '未标注'}", f"校区：{'、'.join(course.get('campus') or []) or '未标注'}",
            f"上课人数：{course.get('current_count') if course.get('current_count') is not None else '未知'} / {course.get('max_count') if course.get('max_count') is not None else '未知'}",
            f"上课地点：{course.get('position') or '未标注'}", f"教师/信息：{course.get('teacher') or '未标注'}",
            f"详情：{SITE_URL}",
        ])
        login_user, sender = smtp_identity(config)
        if not login_user or "@" not in login_user:
            raise ValueError("SMTP 用户名请填写完整邮箱地址（例如 17392553411@163.com）")
        msg = EmailMessage(); msg["Subject"] = subject; msg["From"] = sender; msg["To"] = target; msg.set_content(body)
        host, port = config["smtp_host"], int(config.get("smtp_port") or 465)
        if config.get("smtp_tls", True) or port == 465:
            with smtplib.SMTP_SSL(host, port, timeout=20) as smtp:
                self._login_send(smtp, config, msg, login_user)
        else:
            with smtplib.SMTP(host, port, timeout=20) as smtp:
                smtp.starttls(); self._login_send(smtp, config, msg, login_user)

    def _login_send(self, smtp: smtplib.SMTP, config: dict[str, Any], msg: EmailMessage, login_user: str) -> None:
        if config.get("smtp_username"):
            smtp.login(login_user, VAULT.decrypt(config.get("smtp_password_enc")))
        smtp.send_message(msg)


class BoyaBrowser:
    async def fetch(self, username: str, password: str) -> list[dict[str, Any]]:
        try:
            from playwright.async_api import async_playwright
        except ImportError as exc:
            raise RuntimeError("缺少 Playwright，请执行 pip install -r requirements.txt") from exc
        state_path = DATA_DIR / "browser-state.json"
        async with async_playwright() as pw:
            browser = await pw.chromium.launch(headless=True)
            context = await browser.new_context(
                storage_state=str(state_path) if state_path.exists() else None,
                user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                locale="zh-CN",
                timezone_id="Asia/Shanghai",
            )
            page = await context.new_page()
            await page.goto(SITE_URL, wait_until="domcontentloaded", timeout=45000)
            await page.wait_for_timeout(1500)
            # 博雅前端先进入自己的 /login 页面，再由按钮跳转统一认证。
            jump = page.get_by_role("button", name="点我立即跳转")
            if await jump.count():
                await jump.click()
                await page.wait_for_timeout(1500)
            if "sso.buaa.edu.cn" in page.url or await page.locator("input[type=password]").count():
                await self._login(page, username, password)
                await page.goto(SITE_URL, wait_until="domcontentloaded", timeout=45000)
            await page.wait_for_timeout(2500)
            # 当前站点会把旧的 course-home 地址重定向到首页，必须通过侧栏进入实际选课路由。
            if not await page.locator(".course-table tbody tr").count():
                nav = page.locator(".nav-main").filter(has_text="我的课程").first
                if await nav.count():
                    await nav.click()
                    await page.wait_for_timeout(300)
                    target = page.locator('li[href="/system/course-select"]').first
                    if await target.count():
                        await target.click()
                        await page.wait_for_timeout(2500)
            rows = await page.locator(".course-table tbody tr").evaluate_all("rows => rows.map(r => [...r.querySelectorAll('td')].map(td => td.innerText))")
            if not rows:
                rows = await page.locator("table tbody tr").evaluate_all("rows => rows.map(r => [...r.querySelectorAll('td')].map(td => td.innerText))")
            await context.storage_state(path=str(state_path))
            await browser.close()
            return parse_course_rows(rows)

    async def _login(self, page: Any, username: str, password: str) -> None:
        # 统一认证登录表单目前位于 cas/login-normal.html iframe 中。
        target = page
        for frame in page.frames:
            if frame is page.main_frame:
                continue
            if await frame.locator("input[name=password]:visible").count():
                target = frame
                break
        user = target.locator("input[name=username], input#username, input[type=text]:visible").first
        passwd = target.locator("input[name=password], input#password, input[type=password]:visible").first
        await user.fill(username); await passwd.fill(password)
        await target.locator("button[type=submit], input[type=submit], input[type=button][value=登录], .submit-btn").first.click()
        await page.wait_for_timeout(3000)


class Monitor:
    def __init__(self) -> None:
        self.running = False
        self.last_run: str | None = None
        self.last_error: str | None = None
        self.task: asyncio.Task | None = None
        self.client = BoyaBrowser()
        self.notifier = Notifier()
        self.lock = asyncio.Lock()

    async def run_once(self) -> dict[str, Any]:
        async with self.lock:
            config = STORE.get()
            username, password = config.get("username", ""), VAULT.decrypt(config.get("password_enc"))
            if not username or not password:
                raise ValueError("请先填写博雅账号和密码")
            courses = await self.client.fetch(username, password)
            accepted = []
            changed_count = 0
            notified_count = 0
            for course in courses:
                if not campus_matches(course, config.get("campus", ""), config.get("delivery_mode", "offline")):
                    continue
                if not fits_free_hours(course, config.get("free_hours", {})):
                    continue
                previous = STORE.get_course(course["id"])
                fingerprint = course_fingerprint(course)
                course["fingerprint"] = fingerprint
                # 老记录没有指纹时只建立基线，避免上线改版后批量重复发信。
                if previous and not previous.get("last_notified_fingerprint"):
                    course["last_notified_fingerprint"] = fingerprint
                    changed = False
                else:
                    last_notified = previous.get("last_notified_fingerprint") if previous else None
                    changed = last_notified != fingerprint
                    course["last_notified_fingerprint"] = last_notified
                if changed:
                    changed_count += 1
                    try:
                        self.notifier.send(course, config)
                        course["last_notified_fingerprint"] = fingerprint
                        notified_count += 1
                        STORE.event("success", f"已通知课程更新：{course['name']}")
                    except Exception as exc:
                        STORE.event("error", f"课程更新通知失败（{course['name']}）：{exc}")
                STORE.save_course(course, course.get("last_notified_fingerprint") == fingerprint)
                accepted.append(course)
            self.last_run, self.last_error = datetime.now().isoformat(timespec="seconds"), None
            STORE.event("info", f"监控完成：抓取 {len(courses)} 门，符合条件 {len(accepted)} 门，更新 {changed_count} 门，已通知 {notified_count} 门")
            return {"fetched": len(courses), "accepted": len(accepted), "changed": changed_count, "notified": notified_count, "courses": accepted}

    @staticmethod
    def _selection_open(course: dict[str, Any]) -> bool:
        now = datetime.now()
        start, end = parse_datetime(course.get("select_start")), parse_datetime(course.get("select_end"))
        if start and now < start:
            return False
        if end and now > end:
            return False
        return True

    async def loop(self) -> None:
        self.running = True
        while self.running:
            try:
                await self.run_once()
            except Exception as exc:
                self.last_error = str(exc); STORE.event("error", str(exc)); logger.exception("monitor run failed")
            await asyncio.sleep(effective_poll_interval())


MONITOR = Monitor()


def effective_poll_interval() -> int:
    override = os.getenv("BOYA_POLL_INTERVAL")
    if override:
        try:
            return max(60, int(override))
        except ValueError:
            pass
    return max(60, int(STORE.get().get("poll_interval") or POLL_INTERVAL))


def ensure_monitor_task() -> None:
    """在配置完成后启动单例轮询任务。"""
    if MONITOR.task is None or MONITOR.task.done():
        MONITOR.task = asyncio.create_task(MONITOR.loop())


def public_config() -> dict[str, Any]:
    config = STORE.get()
    config.pop("password_enc", None); config.pop("smtp_password_enc", None)
    config["has_password"] = bool(STORE.get().get("password_enc"))
    config["has_smtp_password"] = bool(STORE.get().get("smtp_password_enc"))
    return config


async def index(request):
    return FileResponse(ROOT / "static" / "index.html")


async def get_config(request):
    return JSONResponse(public_config())


async def save_config(request):
    data = await request.json()
    old = STORE.get()
    config = {**DEFAULT_CONFIG, **old}
    allowed = set(DEFAULT_CONFIG) | {"password", "smtp_password"}
    config.update({k: v for k, v in data.items() if k in allowed})
    # 兼容旧版本把“线上”误存入 campus 的配置。
    if config.get("campus") == "线上":
        config["delivery_mode"], config["campus"] = "online", "学院路"
    if data.get("password"):
        config["password_enc"] = VAULT.encrypt(str(data["password"]))
    if data.get("smtp_password"):
        config["smtp_password_enc"] = VAULT.encrypt(str(data["smtp_password"]))
    config.pop("password", None); config.pop("smtp_password", None)
    config["smtp_port"] = int(config.get("smtp_port") or 465)
    config["poll_interval"] = max(60, int(config.get("poll_interval") or POLL_INTERVAL))
    if config.get("delivery_mode") not in {"offline", "online"}:
        return JSONResponse({"error": "上课形式必须是线下或线上"}, status_code=400)
    if config.get("campus") not in {"学院路", "沙河"}:
        return JSONResponse({"error": "线下校区必须是学院路或沙河"}, status_code=400)
    STORE.save(config)
    if config.get("username") and config.get("password_enc"):
        ensure_monitor_task()
    return JSONResponse(public_config())


async def run_monitor(request):
    try:
        return JSONResponse(await MONITOR.run_once())
    except Exception as exc:
        return JSONResponse({"error": str(exc)}, status_code=400)


async def status(request):
    return JSONResponse({"running": MONITOR.running, "last_run": MONITOR.last_run, "last_error": MONITOR.last_error, "poll_interval": effective_poll_interval(), "events": STORE.events()})


async def courses(request):
    config = STORE.get()
    visible = [
        course for course in STORE.courses()
        if campus_matches(course, config.get("campus", ""), config.get("delivery_mode", "offline"))
        and fits_free_hours(course, config.get("free_hours", {}))
    ]
    return JSONResponse(visible)


async def test_notify(request):
    data = await request.json()
    config = STORE.get(); config.update(data)
    if data.get("smtp_password"):
        config["smtp_password_enc"] = VAULT.encrypt(str(data["smtp_password"]))
    else:
        config["smtp_password_enc"] = config.get("smtp_password_enc") or STORE.get().get("smtp_password_enc")
    try:
        MONITOR.notifier.send(normalize_course({"name": "博雅通知测试", "start": datetime.now().isoformat(sep=" ", timespec="minutes"), "select_start": datetime.now().isoformat(sep=" ", timespec="minutes"), "category": "测试课程", "campus": config.get("campus")}), config)
        STORE.event("success", "测试通知发送成功")
        return JSONResponse({"ok": True})
    except Exception as exc:
        STORE.event("error", f"测试通知失败：{exc}")
        return JSONResponse({"error": str(exc)}, status_code=400)


@asynccontextmanager
async def lifespan(app):
    saved = STORE.get()
    if saved.get("username") and saved.get("password_enc"):
        ensure_monitor_task()
    yield
    MONITOR.running = False
    if MONITOR.task:
        MONITOR.task.cancel()


routes = [Route("/", index), Route("/api/config", get_config, methods=["GET"]), Route("/api/config", save_config, methods=["POST"]), Route("/api/monitor/run", run_monitor, methods=["POST"]), Route("/api/status", status), Route("/api/courses", courses), Route("/api/notify/test", test_notify, methods=["POST"]), Mount("/static", app=StaticFiles(directory=ROOT / "static"), name="static")]
app = CORSMiddleware(Starlette(routes=routes, lifespan=lifespan), allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

