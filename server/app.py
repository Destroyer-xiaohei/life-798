# -*- coding: utf-8 -*-
"""慧生活798 · 积分托管服务端
=================================

App 把账号（token / uid / eid / 手机号）同步上来，服务端每天定时把当天所有能领的
积分一次领完，并把每一步写进日志；App 随时可以拉取账号列表、运行记录和日志。

零第三方依赖：仅标准库（requests 有则用，没有就退回 urllib）。

    python app.py                    启动服务 + 内置定时器
    python app.py --once             立刻跑一轮后退出（可挂 Windows 计划任务）
    python app.py --port 8787        指定端口
    python app.py --run 3            只跑 3 号账号

启动后打印的 ApiKey 填到 App 的「自动领取」页面即可。
"""

from __future__ import annotations

import json
import os
import secrets
import socket
import sys
import threading
import time
import traceback
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional
from urllib.parse import parse_qs, urlparse

try:  # Windows 控制台默认 GBK，强制 UTF-8，避免中文日志乱码 / 编码异常
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from ilife import BASE_URL, IlifeScoreClient, TokenExpired, run_account  # noqa: E402

DEFAULT_HOST = BASE_URL
from store import Store  # noqa: E402

CN_TZ = timezone(timedelta(hours=8))

DATA_DIR = os.environ.get("ILIFE_DATA_DIR") or os.path.join(BASE_DIR, "data")
os.makedirs(DATA_DIR, exist_ok=True)

HOST = os.environ.get("ILIFE_HOST", "0.0.0.0")
PORT = int(os.environ.get("ILIFE_PORT", "4848"))
RUN_HOUR = int(os.environ.get("ILIFE_RUN_HOUR", "0"))
RUN_MINUTE = int(os.environ.get("ILIFE_RUN_MINUTE", "5"))
# 客户端每个任务之间固定等 30s；服务端默认对齐，可用 ILIFE_CLAIM_DELAY 调整。
CLAIM_DELAY = float(os.environ.get("ILIFE_CLAIM_DELAY", "30"))
MAX_PER_TASK = int(os.environ.get("ILIFE_MAX_PER_TASK", "30"))
LOG_DAYS = int(os.environ.get("ILIFE_LOG_DAYS", "30"))

KEY_FILE = os.path.join(DATA_DIR, "api_key.txt")


def load_api_key() -> str:
    env_key = (os.environ.get("ILIFE_API_KEY") or "").strip()
    if env_key:
        return env_key
    if os.path.exists(KEY_FILE):
        try:
            with open(KEY_FILE, "r", encoding="utf-8") as handle:
                key = handle.read().strip()
            if key:
                return key
        except Exception:
            pass
    key = secrets.token_urlsafe(24)
    try:
        with open(KEY_FILE, "w", encoding="utf-8") as handle:
            handle.write(key)
    except Exception:
        pass
    return key


API_KEY = load_api_key()
STORE = Store(os.path.join(DATA_DIR, "ilife.db"))
RUN_LOCK = threading.RLock()
RUN_STATE: Dict[str, Any] = {"running": False, "account_id": 0, "since": 0}


# --------------------------------------------------------------------- 执行器
def make_logger(account_id: int, run_id: int):
    def _log(level: str, message: str, task: Optional[str] = None, delta: int = 0) -> None:
        STORE.add_log(account_id, run_id, level, task or "", message or "", delta or 0)
        stamp = datetime.now(CN_TZ).strftime("%H:%M:%S")
        prefix = "[%s] %s" % (stamp, level.upper().ljust(7))
        if task:
            prefix += " [%s]" % task
        print("%s %s" % (prefix, message), flush=True)

    return _log


def run_one_account(account: Dict[str, Any], trigger: str = "manual") -> Dict[str, Any]:
    account_id = int(account["id"])
    name = account.get("name") or account.get("phone") or ("账号%s" % account_id)
    run_id = STORE.create_run(account_id, name, trigger)
    log = make_logger(account_id, run_id)
    log("info", "开始领取（%s）" % trigger)

    summary: Dict[str, Any] = {}
    status = "ok"
    message = ""
    try:
        summary = run_account(account, log, delay=CLAIM_DELAY,
                              max_per_task=MAX_PER_TASK)
        claimed = int(summary.get("claimed") or 0)
        status = "ok" if claimed > 0 else "empty"
        message = "领取 %d 次，积分 %s -> %s" % (
            claimed, summary.get("score_before"), summary.get("score_after"))
    except TokenExpired as exc:
        status = "token_expired"
        message = str(exc) or "登录状态已过期"
        log("error", "登录状态已过期：请在 App 里重新登录后再同步一次账号")
    except Exception as exc:  # noqa: BLE001
        status = "error"
        message = str(exc)
        log("error", "执行失败：%s" % exc)
        traceback.print_exc()
    finally:
        claimed = int(summary.get("claimed") or 0)
        points = int(summary.get("points") or 0)
        STORE.finish_run(run_id, status, claimed, points,
                         str(summary.get("score_before") or ""),
                         str(summary.get("score_after") or ""), message)
        STORE.set_account_run(account_id, status)

    return {"run_id": run_id, "account_id": account_id, "status": status,
            "message": message, "claimed": int(summary.get("claimed") or 0),
            "points": int(summary.get("points") or 0)}


def run_all(trigger: str = "manual", account_id: Optional[int] = None) -> List[Dict[str, Any]]:
    results: List[Dict[str, Any]] = []
    with RUN_LOCK:
        RUN_STATE["running"] = True
        RUN_STATE["since"] = int(time.time() * 1000)
        try:
            accounts = STORE.list_accounts()
            for account in accounts:
                if account_id and int(account["id"]) != int(account_id):
                    continue
                if not int(account.get("enabled", 1)):
                    continue
                RUN_STATE["account_id"] = int(account["id"])
                results.append(run_one_account(account, trigger))
        finally:
            RUN_STATE["running"] = False
            RUN_STATE["account_id"] = 0
    return results


def run_all_async(trigger: str = "manual", account_id: Optional[int] = None) -> None:
    threading.Thread(target=run_all, args=(trigger, account_id), daemon=True).start()


def scheduler_loop(stop_event: threading.Event) -> None:
    print("[scheduler] 每日 %02d:%02d (Asia/Shanghai) 自动领取，等待中..." % (RUN_HOUR, RUN_MINUTE),
          flush=True)
    while not stop_event.wait(20):
        now = datetime.now(CN_TZ)
        today = now.strftime("%Y-%m-%d")
        if STORE.kv_get("last_daily") == today:
            continue
        if (now.hour, now.minute) < (RUN_HOUR, RUN_MINUTE):
            continue
        STORE.kv_set("last_daily", today)
        print("[scheduler] %s 触发每日自动领取" % now.strftime("%Y-%m-%d %H:%M:%S"), flush=True)
        try:
            run_all("daily")
        except Exception:  # noqa: BLE001
            traceback.print_exc()
        try:
            STORE.prune(LOG_DAYS)
        except Exception:  # noqa: BLE001
            pass


# ------------------------------------------------------------------ HTTP 层
def public_account(account: Dict[str, Any]) -> Dict[str, Any]:
    out = dict(account)
    token = out.get("token") or ""
    out["token"] = ("%s****%s" % (token[:6], token[-6:])) if len(token) > 14 else "****"
    out["has_token"] = bool(token)
    app_token = out.get("app_token") or ""
    out["app_token"] = ("%s****%s" % (app_token[:6], app_token[-6:])) \
        if len(app_token) > 14 else "****"
    out["has_app_token"] = bool(app_token)
    out["enabled"] = bool(int(out.get("enabled", 1)))
    out["host"] = out.get("host") or DEFAULT_HOST
    return out


class Handler(BaseHTTPRequestHandler):
    server_version = "WaterPoints/1.0"
    protocol_version = "HTTP/1.1"
    # keep-alive 空闲连接最多挂 65s，避免手机端断网后线程一直吊着。
    timeout = 65

    # 手机端切网 / 退后台 / HttpURLConnection.disconnect() 都会直接甩 RST，
    # 此时读请求行或写响应会抛连接类异常。这是常态噪音，直接关连接即可。
    _CONN_ERRORS = (ConnectionResetError, ConnectionAbortedError,
                    BrokenPipeError, TimeoutError, socket.timeout)

    def handle(self) -> None:  # noqa: N802
        try:
            super().handle()
        except self._CONN_ERRORS:
            self.close_connection = True

    def handle_one_request(self) -> None:  # noqa: N802
        try:
            super().handle_one_request()
        except self._CONN_ERRORS:
            self.close_connection = True

    # ---------------------------------------------------------------- helpers
    def log_message(self, fmt: str, *args: Any) -> None:  # 静音默认访问日志
        return

    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Api-Key")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")

    def _send(self, status: int, payload: Any, ctype: str = "application/json; charset=utf-8") -> None:
        if isinstance(payload, (dict, list)):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        elif isinstance(payload, str):
            body = payload.encode("utf-8")
        else:
            body = payload
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _ok(self, data: Any = None, msg: str = "ok") -> None:
        self._send(200, {"code": 0, "msg": msg, "data": data})

    def _err(self, status: int, msg: str, code: int = -1) -> None:
        self._send(status, {"code": code, "msg": msg, "data": None})

    def _auth_ok(self) -> bool:
        key = self.headers.get("X-Api-Key") or ""
        if not key:
            key = (parse_qs(urlparse(self.path).query).get("key") or [""])[0]
        if key and key == API_KEY:
            return True
        self._err(401, "ApiKey 不正确")
        return False

    def _read_json(self) -> Dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except Exception:
            length = 0
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except Exception:
            return {}
        return data if isinstance(data, dict) else {}

    # ---------------------------------------------------------------- routing
    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        query = parse_qs(parsed.query)

        if path == "/" or path == "/index.html":
            self._send(200, self._page(), "text/html; charset=utf-8")
            return
        if not self._auth_ok():
            return

        if path == "/api/health":
            self._ok({"running": RUN_STATE["running"], "now": int(time.time() * 1000),
                      "run_time": "%02d:%02d" % (RUN_HOUR, RUN_MINUTE),
                      "accounts": len(STORE.list_accounts())})
            return
        if path == "/api/accounts":
            self._ok([public_account(a) for a in STORE.list_accounts()])
            return
        if path == "/api/logs":
            account_id = int((query.get("account_id") or ["0"])[0] or 0)
            limit = min(int((query.get("limit") or ["200"])[0] or 200), 2000)
            level = (query.get("level") or [""])[0]
            since_id = int((query.get("since_id") or ["0"])[0] or 0)
            self._ok(STORE.list_logs(account_id or None, limit, level, since_id))
            return
        if path == "/api/runs":
            account_id = int((query.get("account_id") or ["0"])[0] or 0)
            limit = min(int((query.get("limit") or ["50"])[0] or 50), 500)
            self._ok(STORE.list_runs(account_id or None, limit))
            return
        self._err(404, "接口不存在")

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        if not self._auth_ok():
            return

        if path == "/api/accounts":
            body = self._read_json()
            token = str(body.get("token") or "").strip()
            app_token = str(body.get("app_token") or "").strip()
            uid = str(body.get("uid") or "").strip()
            if not uid:
                self._err(400, "uid 必填")
                return
            if not token and not app_token:
                self._err(400, "至少需要 token 或 app_token 之一")
                return
            account = STORE.upsert_account(
                phone=str(body.get("phone") or ""),
                token=token,
                uid=uid,
                eid=str(body.get("eid") or ""),
                name=str(body.get("name") or ""),
                host=str(body.get("host") or ""),
                enabled=bool(body.get("enabled", True)),
                app_token=app_token,
            )
            self._ok(public_account(account), "账号已同步")
            return

        if path == "/api/run":
            body = self._read_json()
            account_id = body.get("account_id")
            run_all_async("manual", int(account_id) if account_id else None)
            self._ok({"started": True})
            return

        if path.startswith("/api/accounts/") and path.endswith("/run"):
            parts = path.split("/")
            try:
                account_id = int(parts[3])
            except Exception:
                self._err(400, "账号 id 不合法")
                return
            if not STORE.get_account(account_id):
                self._err(404, "账号不存在")
                return
            run_all_async("manual", account_id)
            self._ok({"started": True, "account_id": account_id})
            return

        self._err(404, "接口不存在")

    def do_DELETE(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        if not self._auth_ok():
            return
        if path.startswith("/api/accounts/"):
            try:
                account_id = int(path.split("/")[3])
            except Exception:
                self._err(400, "账号 id 不合法")
                return
            ok = STORE.delete_account(account_id)
            self._ok({"deleted": bool(ok)})
            return
        self._err(404, "接口不存在")

    # ------------------------------------------------------------------- 首页
    def _page(self) -> str:
        accounts = STORE.list_accounts()
        rows = "".join(
            "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>" % (
                a["id"], a.get("name") or a.get("phone") or "-", a.get("uid", "")[:12],
                a.get("last_status") or "-",
                datetime.fromtimestamp((a.get("last_run_at") or 0) / 1000, CN_TZ).strftime("%m-%d %H:%M")
                if a.get("last_run_at") else "-")
            for a in accounts)
        logs = STORE.list_logs(None, 60)
        log_rows = "".join(
            "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>" % (
                datetime.fromtimestamp((entry.get("ts") or 0) / 1000, CN_TZ).strftime("%m-%d %H:%M:%S"),
                entry.get("account_id"), entry.get("level"), entry.get("task") or "",
                (entry.get("message") or "").replace("<", "&lt;"))
            for entry in logs)
        return (
            "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
"<title>出水小部件 · 积分托管</title><meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<style>body{font-family:-apple-system,Segoe UI,Microsoft YaHei,sans-serif;margin:24px;"
            "background:#fafafa;color:#222}h1{font-size:20px}table{border-collapse:collapse;width:100%;"
            "background:#fff;font-size:13px;margin-bottom:24px}td,th{border:1px solid #eee;padding:6px 8px;"
            "text-align:left}th{background:#f2f2f2}code{background:#eee;padding:2px 6px;border-radius:4px}"
            "</style></head><body>"
            "<h1>出水小部件 · 积分托管服务（慧生活798）</h1>"
            "<p>每日执行时间：" + "%02d:%02d" % (RUN_HOUR, RUN_MINUTE) + "（Asia/Shanghai），"
            "ApiKey：<code>" + API_KEY + "</code></p>"
            "<h3>账号（" + str(len(accounts)) + "）</h3><table>"
            "<tr><th>ID</th><th>名称</th><th>UID</th><th>状态</th><th>上次执行</th></tr>"
            + rows + "</table>"
            "<h3>最近日志</h3><table>"
            "<tr><th>时间</th><th>账号</th><th>级别</th><th>任务</th><th>内容</th></tr>"
            + log_rows + "</table></body></html>"
        )


class QuietThreadingHTTPServer(ThreadingHTTPServer):
    """手机端断线属于常态，连接类异常不再打印 traceback 刷屏。"""

    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request, client_address) -> None:  # noqa: N802
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionResetError, ConnectionAbortedError,
                            BrokenPipeError, TimeoutError, socket.timeout)):
            return
        super().handle_error(request, client_address)


def main() -> None:
    argv = sys.argv[1:]

    if "--once" in argv:
        print("开始执行一轮领取...", flush=True)
        results = run_all("cli")
        print(json.dumps(results, ensure_ascii=False, indent=2), flush=True)
        return
    if "--run" in argv:
        index = argv.index("--run")
        account_id = int(argv[index + 1]) if index + 1 < len(argv) else 0
        print(json.dumps(run_all("cli", account_id or None), ensure_ascii=False, indent=2))
        return

    port = PORT
    if "--port" in argv:
        index = argv.index("--port")
        if index + 1 < len(argv):
            port = int(argv[index + 1])

    stop_event = threading.Event()
    threading.Thread(target=scheduler_loop, args=(stop_event,), daemon=True).start()

    server = QuietThreadingHTTPServer((HOST, port), Handler)
    print("=" * 62, flush=True)
    print(" 出水小部件 · 积分托管服务已启动", flush=True)
    print(" 监听地址 : http://%s:%d" % (HOST, port), flush=True)
    print(" ApiKey   : %s" % API_KEY, flush=True)
    print(" 每日执行 : %02d:%02d (Asia/Shanghai)" % (RUN_HOUR, RUN_MINUTE), flush=True)
    print(" 数据目录 : %s" % DATA_DIR, flush=True)
    print("=" * 62, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("bye", flush=True)
    finally:
        stop_event.set()


if __name__ == "__main__":
    main()
