# -*- coding: utf-8 -*-
"""SQLite 存储层：账号 / 运行记录 / 领取日志。"""

from __future__ import annotations

import sqlite3
import threading
import time
from typing import Any, Dict, List, Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS accounts (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    phone       TEXT    NOT NULL DEFAULT '',
    name        TEXT    NOT NULL DEFAULT '',
    token       TEXT    NOT NULL DEFAULT '',
    app_token   TEXT    NOT NULL DEFAULT '',
    uid         TEXT    NOT NULL DEFAULT '',
    eid         TEXT    NOT NULL DEFAULT '',
    host        TEXT    NOT NULL DEFAULT '',
    enabled     INTEGER NOT NULL DEFAULT 1,
    created_at  INTEGER NOT NULL DEFAULT 0,
    updated_at  INTEGER NOT NULL DEFAULT 0,
    last_run_at INTEGER NOT NULL DEFAULT 0,
    last_status TEXT    NOT NULL DEFAULT ''
);
-- 同一 uid 在两个平台的 token 互不通用，账号身份按 (host, uid) 唯一
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_host_uid ON accounts(host, uid) WHERE uid <> '';

CREATE TABLE IF NOT EXISTS runs (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id   INTEGER NOT NULL,
    account_name TEXT    NOT NULL DEFAULT '',
    started_at   INTEGER NOT NULL DEFAULT 0,
    finished_at  INTEGER NOT NULL DEFAULT 0,
    status       TEXT    NOT NULL DEFAULT 'running',
    trigger      TEXT    NOT NULL DEFAULT 'manual',
    claimed      INTEGER NOT NULL DEFAULT 0,
    points       INTEGER NOT NULL DEFAULT 0,
    score_before TEXT    NOT NULL DEFAULT '',
    score_after  TEXT    NOT NULL DEFAULT '',
    message      TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_runs_account ON runs(account_id, started_at);

CREATE TABLE IF NOT EXISTS logs (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    run_id     INTEGER NOT NULL DEFAULT 0,
    ts         INTEGER NOT NULL DEFAULT 0,
    level      TEXT    NOT NULL DEFAULT 'info',
    task       TEXT    NOT NULL DEFAULT '',
    message    TEXT    NOT NULL DEFAULT '',
    delta      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_logs_account ON logs(account_id, ts);
CREATE INDEX IF NOT EXISTS idx_logs_run ON logs(run_id);

CREATE TABLE IF NOT EXISTS kv (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL DEFAULT ''
);
"""


class Store:
    def __init__(self, path: str) -> None:
        self.path = str(path)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False, timeout=30)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=NORMAL")
            self._conn.executescript(SCHEMA)
            self._migrate()

    def _migrate(self) -> None:
        """老库补列/换索引：host / app_token 列 + (host, uid) 唯一索引。"""
        cols = {row[1] for row in self._conn.execute("PRAGMA table_info(accounts)").fetchall()}
        if "host" not in cols:
            self._conn.execute("ALTER TABLE accounts ADD COLUMN host TEXT NOT NULL DEFAULT ''")
        if "app_token" not in cols:
            self._conn.execute(
                "ALTER TABLE accounts ADD COLUMN app_token TEXT NOT NULL DEFAULT ''")
        self._conn.execute("DROP INDEX IF EXISTS idx_accounts_uid")
        self._conn.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_host_uid "
            "ON accounts(host, uid) WHERE uid <> ''")
        self._conn.commit()

    # ------------------------------------------------------------------ 基础
    def _exec(self, sql: str, args: tuple = ()) -> sqlite3.Cursor:
        with self._lock:
            cur = self._conn.execute(sql, args)
            self._conn.commit()
            return cur

    def _query(self, sql: str, args: tuple = ()) -> List[Dict[str, Any]]:
        with self._lock:
            cur = self._conn.execute(sql, args)
            return [dict(row) for row in cur.fetchall()]

    def _one(self, sql: str, args: tuple = ()) -> Optional[Dict[str, Any]]:
        rows = self._query(sql, args)
        return rows[0] if rows else None

    # ------------------------------------------------------------------ kv
    def kv_get(self, key: str, default: str = "") -> str:
        row = self._one("SELECT v FROM kv WHERE k = ?", (key,))
        return row["v"] if row else default

    def kv_set(self, key: str, value: str) -> None:
        self._exec("INSERT INTO kv(k, v) VALUES(?, ?) "
                   "ON CONFLICT(k) DO UPDATE SET v = excluded.v", (key, str(value)))

    # -------------------------------------------------------------- accounts
    def upsert_account(self, phone: str = "", token: str = "", uid: str = "",
                       eid: str = "", name: str = "", host: str = "",
                       enabled: bool = True, app_token: str = "") -> Dict[str, Any]:
        now = int(time.time() * 1000)
        uid = (uid or "").strip()
        phone = (phone or "").strip()
        host = (host or "").strip().rstrip("/")
        app_token = (app_token or "").strip()
        existing = None
        if uid:
            existing = self._one("SELECT * FROM accounts WHERE uid = ? AND host = ?", (uid, host))
        if existing is None and phone:
            existing = self._one("SELECT * FROM accounts WHERE phone = ? AND host = ?", (phone, host))
        if existing:
            self._exec(
                "UPDATE accounts SET phone=?, name=?, token=?, app_token=?, uid=?, eid=?, "
                "host=?, enabled=?, updated_at=? WHERE id=?",
                (phone or existing["phone"], name or existing["name"],
                 token or existing["token"], app_token or existing["app_token"],
                 uid or existing["uid"], eid or existing["eid"],
                 host or existing["host"], 1 if enabled else 0, now, existing["id"]),
            )
            return self.get_account(existing["id"]) or {}
        cur = self._exec(
            "INSERT INTO accounts(phone, name, token, app_token, uid, eid, host, enabled, "
            "created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            (phone, name, token, app_token, uid, eid, host, 1 if enabled else 0, now, now),
        )
        return self.get_account(cur.lastrowid) or {}

    def list_accounts(self) -> List[Dict[str, Any]]:
        return self._query("SELECT * FROM accounts ORDER BY id ASC")

    def get_account(self, account_id: int) -> Optional[Dict[str, Any]]:
        return self._one("SELECT * FROM accounts WHERE id = ?", (int(account_id),))

    def delete_account(self, account_id: int) -> bool:
        cur = self._exec("DELETE FROM accounts WHERE id = ?", (int(account_id),))
        self._exec("DELETE FROM logs WHERE account_id = ?", (int(account_id),))
        self._exec("DELETE FROM runs WHERE account_id = ?", (int(account_id),))
        return cur.rowcount > 0

    def set_account_run(self, account_id: int, status: str, run_at: int = 0) -> None:
        self._exec("UPDATE accounts SET last_run_at=?, last_status=? WHERE id=?",
                   (int(run_at or time.time() * 1000), status, int(account_id)))

    # ------------------------------------------------------------------ runs
    def create_run(self, account_id: int, account_name: str, trigger: str = "manual") -> int:
        cur = self._exec(
            "INSERT INTO runs(account_id, account_name, started_at, status, trigger) "
            "VALUES(?,?,?,?,?)",
            (int(account_id), account_name, int(time.time() * 1000), "running", trigger),
        )
        return int(cur.lastrowid)

    def finish_run(self, run_id: int, status: str, claimed: int, points: int,
                   score_before: str, score_after: str, message: str) -> None:
        self._exec(
            "UPDATE runs SET finished_at=?, status=?, claimed=?, points=?, score_before=?, "
            "score_after=?, message=? WHERE id=?",
            (int(time.time() * 1000), status, int(claimed), int(points),
             str(score_before), str(score_after), message, int(run_id)),
        )

    def list_runs(self, account_id: Optional[int] = None, limit: int = 50) -> List[Dict[str, Any]]:
        if account_id:
            return self._query("SELECT * FROM runs WHERE account_id=? ORDER BY id DESC LIMIT ?",
                               (int(account_id), int(limit)))
        return self._query("SELECT * FROM runs ORDER BY id DESC LIMIT ?", (int(limit),))

    def get_run(self, run_id: int) -> Optional[Dict[str, Any]]:
        return self._one("SELECT * FROM runs WHERE id = ?", (int(run_id),))

    def prune(self, log_days: int = 30) -> None:
        cutoff = int((time.time() - log_days * 86400) * 1000)
        self._exec("DELETE FROM logs WHERE ts < ?", (cutoff,))

    # ------------------------------------------------------------------ logs
    def add_log(self, account_id: int, run_id: int, level: str, task: str,
                message: str, delta: int = 0) -> None:
        self._exec(
            "INSERT INTO logs(account_id, run_id, ts, level, task, message, delta) "
            "VALUES(?,?,?,?,?,?,?)",
            (int(account_id), int(run_id), int(time.time() * 1000), level or "info",
             task or "", message or "", int(delta or 0)),
        )

    def list_logs(self, account_id: Optional[int] = None, limit: int = 200,
                  level: str = "", since_id: int = 0) -> List[Dict[str, Any]]:
        sql = "SELECT * FROM logs WHERE id > ?"
        args: List[Any] = [int(since_id or 0)]
        if account_id:
            sql += " AND account_id = ?"
            args.append(int(account_id))
        if level:
            sql += " AND level = ?"
            args.append(level)
        sql += " ORDER BY id DESC LIMIT ?"
        args.append(int(limit))
        return self._query(sql, tuple(args))
