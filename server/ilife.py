# -*- coding: utf-8 -*-
"""
慧生活 798 · 积分接口客户端 + 一键领取引擎
==========================================

所有接口与签名算法来自对官方 App（慧生活798 3.1.7）的静态还原：

* GET  /api/v1/acc/score/mission-lst
       -> BaseReq<TaskData> = { accScoreRsp, dailyRSP, lotteryEnable, missions[] }
       accScoreRsp.limits[] 给出每个任务当天的可领取/已领取计数
       accScoreRsp.daily.week 是 7 位二进制签到位图（bit0 = 周一）
* POST /api/v1/acc/score/score-send?sign=<md5>&s=true
       任务领取 body = { adId, type: 101 }
       每日签到 body = { adId, weekDay: 1..7 }   <- 大写 D，来自 PointsAdInfo
  官方请求体类（3.1.7 dex 还原）：
       AddScoreParm  = { adId, addScore, addScoreType, type, weekday }
       PointsAdInfo  = { adId, adName, ip, score, sn, type, uid, weekDay }
  客户端只发送上面列出的最小字段；签到若混入 addScore/addScoreType/小写
  weekday，会被服务端当成无效请求，因此服务端按客户端逻辑原样复刻。
* 签名 md5(adId + str(v20) + token[-8:] + uid[-8:] + SALT)
       v20 = 10 * ((serverTs - localTs + nowMs) // 10000)
  该算法逐条指令核对自 libnative_crypto.so 的
  Java_com_ilife_lib_common_util_NativeCryptoUtils_nativeSign。
  客户端 App 直接用本机时钟做 10 秒分桶，因此服务端只在时钟偏差小于
  5 分钟时才套用 (serverTs - localTs) 偏移。
"""

from __future__ import annotations

import hashlib
import json
import os
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, List, Optional, Tuple

try:
    import requests as _requests
    _HAS_REQUESTS = True
except Exception:  # pragma: no cover
    _requests = None
    _HAS_REQUESTS = False

import urllib.error
import urllib.parse
import urllib.request

# 默认对接慧生活798；同一套契约的白标平台可通过环境变量换成自己的网关与盐值。
BASE_URL = (os.environ.get("ILIFE_BASE_URL") or "https://i.ilife798.com").rstrip("/")
SIGN_SALT = os.environ.get("ILIFE_SIGN_SALT") or "aslkdvcniu34h9tgufh278wv2"
# 服务端按客户端版本号做最低版本校验，过低会被平台拒绝并提示“请升级最新版app”，
# 因此这里与官方客户端（慧生活798 3.1.9）保持一致；如需跟随平台抬高的下限，改这里或设置
# 环境变量 ILIFE_APP_VERSION。
APP_VERSION = os.environ.get("ILIFE_APP_VERSION") or "3.1.7"

CN_TZ = timezone(timedelta(hours=8))

# TaskType（来自 com.ilife.lib.coremodel.data.enums.TaskType）
TYPE_CHECK_IN = 1
TYPE_WATCH_AD = 2
TYPE_RETROACTIVE_CHECK_IN = 3
TYPE_FULL_AD = 4
TYPE_HZML_NOV = 5
TYPE_TAOBAO_SHANGOU = 6

# 积分任务提交时客户端固定带 type=101（收入）
TYPE_SCORE_SEND = 101

# ApplicationType：客户端对积分接口用 1,5（账户服务），设备接口用 1,1。
APP_TYPE_MAIN = "1,5"
APP_TYPE_APP = "1,1"

# 服务端返回码
CODE_OK = 0
CODE_RATE_LIMIT = -98
CODE_TOKEN_EXPIRED = -99

class ClaimNetworkError(Exception):
    """网络层失败（超时 / DNS / TLS 等）。"""


class TokenExpired(Exception):
    """登录态失效，需要在 App 里重新登录后重新同步账号。"""


def now_ms() -> int:
    return int(time.time() * 1000)


def normalize_ts(value: Any, fallback: int) -> int:
    """把服务端返回的时间统一成毫秒（BaseReq.time 实测为毫秒）。"""
    try:
        ts = int(value)
    except Exception:
        return fallback
    if ts <= 0:
        return fallback
    if ts < 100_000_000_000:  # 10 位秒级时间戳
        ts *= 1000
    if ts > 10_000_000_000_000:  # 明显异常，退回本地时间
        return fallback
    return ts


def make_sign(ad_id: str, token: str, uid: str, local_ts: int, server_ts: int,
              now: Optional[int] = None) -> str:
    """复刻 nativeSign 的签名：md5(adId + v20 + token尾8 + uid尾8 + salt)。

    v20 = 10 * ((serverTs - localTs + nowMs) // 10000)，与官方 App 一致。
    只有服务端与本地时钟偏差在 5 分钟内才套用该偏移，否则退回本地时间，
    避免一次时钟异常让所有签名整体失效。
    """
    ts = now if now is not None else now_ms()
    offset = 0
    if server_ts > 0 and local_ts > 0:
        delta = int(server_ts) - int(local_ts)
        if abs(delta) <= 5 * 60 * 1000:
            offset = delta
    v20 = 10 * ((offset + int(ts)) // 10000)
    raw = "%s%d%s%s%s" % (ad_id, v20, (token or "")[-8:], (uid or "")[-8:], SIGN_SALT)
    return hashlib.md5(raw.encode("utf-8")).hexdigest()


def parse_week_mask(value: Any) -> int:
    try:
        return int(value)
    except Exception:
        return 0


def today_weekday() -> int:
    """1 = 周一 ... 7 = 周日（北京时间）。"""
    return datetime.now(CN_TZ).isoweekday()


def signed_today(week_mask: int) -> bool:
    return bool((parse_week_mask(week_mask) >> (today_weekday() - 1)) & 1)


# 客户端 TaskMissionPlanner.shouldSkip 的同一份过滤规则
SKIP_MISSION_KEYWORDS = ("免费权益", "借贷", "贷款")


def _should_skip_mission(name: str) -> bool:
    return any(keyword in (name or "") for keyword in SKIP_MISSION_KEYWORDS)


def _merge_missions(main_missions: Any, app_missions: Any) -> List[Dict[str, Any]]:
    """客户端 TaskMissionPlanner.merge 的等价实现：按 refId 去重，主平台优先。"""
    out: List[Dict[str, Any]] = []
    seen = set()
    for platform, arr in (("main", main_missions), ("app", app_missions)):
        if not isinstance(arr, list):
            continue
        for mission in arr:
            if not isinstance(mission, dict):
                continue
            ref_id = str(mission.get("refId") or "").strip()
            if not ref_id or ref_id in seen:
                continue
            seen.add(ref_id)
            item = dict(mission)
            item["__platform"] = platform
            out.append(item)
    return out


def _http(method: str, url: str, headers: Dict[str, str],
          params: Optional[Dict[str, Any]] = None,
          body: Optional[Dict[str, Any]] = None,
          timeout: int = 20) -> Tuple[int, bytes]:
    if params:
        url = url + "?" + urllib.parse.urlencode(params)
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")

    last_error = None
    for attempt in range(3):
        try:
            if _HAS_REQUESTS:
                resp = _requests.request(method, url, headers=headers, data=payload,
                                         timeout=timeout)
                return resp.status_code, resp.content
            req = urllib.request.Request(url, data=payload, headers=headers, method=method)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as exc:  # 4xx/5xx 也当正常响应处理
            try:
                return exc.code, exc.read()
            except Exception:
                last_error = exc
        except Exception as exc:
            last_error = exc
        if attempt < 2:
            time.sleep(1.5 * (attempt + 1))
    raise ClaimNetworkError(str(last_error))


class IlifeScoreClient:
    """单个账号的积分接口客户端。"""

    def __init__(self, token: str, uid: str, eid: str = "", phone: str = "",
                 version: str = APP_VERSION, timeout: int = 20,
                 host: str = BASE_URL, app_token: str = "") -> None:
        self.base_url = (host or BASE_URL).rstrip("/")
        self.token = (token or "").strip()
        # 第二个 token（小程序 / 官方 App 登录态）。与 token 不同时，客户端会把两套
        # 任务合并去重，服务端保持同样行为。
        self.app_token = (app_token or "").strip()
        self.uid = (uid or "").strip()
        self.eid = (eid or "").strip()
        self.phone = (phone or "").strip()
        self.version = version
        self.timeout = timeout
        self.local_ts = 0
        self.server_ts = 0
        # 触发 -98 限流后的等待秒数（客户端为 60s，测试可覆盖成 0）。
        self.rate_limit_wait = 60
        self.last_code = 0
        self.last_msg = ""
        self._app_client: Optional["IlifeScoreClient"] = None

    def _app_client_for(self) -> Optional["IlifeScoreClient"]:
        """第二个 token 的客户端；没有第二套 token 时返回 None。

        客户端对积分接口（mission-lst / score-lst / score-send）统一使用
        ApplicationType=1,5，只有设备接口才用 1,1，所以两个 token 走同一套请求头。
        """
        if not self.app_token or self.app_token == self.token:
            return None
        if self._app_client is None:
            self._app_client = IlifeScoreClient(
                token=self.app_token,
                uid=self.uid,
                eid=self.eid,
                phone=self.phone,
                version=self.version,
                timeout=self.timeout,
                host=self.base_url,
            )
        self._app_client.rate_limit_wait = self.rate_limit_wait
        return self._app_client

    # ---------------------------------------------------------------- 基础
    def _headers(self, app_type: str = APP_TYPE_MAIN) -> Dict[str, str]:
        headers = {
            "ApplicationType": app_type,
            "VersionCode": self.version,
            "user-agent": "Android_ilife798_%s" % self.version,
            "Accept-Language": "zh-Hans-CN;q=1",
            "Content-Type": "application/json",
        }
        if self.token:
            headers["Authorization"] = self.token
        return headers

    def _call(self, method: str, path: str,
              params: Optional[Dict[str, Any]] = None,
              body: Optional[Dict[str, Any]] = None,
              app_type: str = APP_TYPE_MAIN) -> Dict[str, Any]:
        status, raw = _http(method, self.base_url + path, self._headers(app_type),
                            params, body, self.timeout)
        text = raw.decode("utf-8", "replace") if raw else ""
        try:
            data = json.loads(text) if text else {}
        except Exception as exc:
            raise ClaimNetworkError("响应不是合法 JSON: %s (%s)" % (text[:200], exc))
        if not isinstance(data, dict):
            data = {"code": -1, "data": data}
        # 注意：code=0 是成功，不能用 `or -1`（0 是 falsy 会被误判成失败）。
        try:
            self.last_code = int(data.get("code", -1))
        except (TypeError, ValueError):
            self.last_code = -1
        self.last_msg = str(data.get("msg") or "")
        return data

    def get_mission_list(self) -> Dict[str, Any]:
        """拉取任务目录，并记录本地 / 服务端时间戳用于签名。"""
        self.local_ts = now_ms()
        resp = self._call("GET", "/api/v1/acc/score/mission-lst")
        self.server_ts = normalize_ts(resp.get("time"), self.local_ts)
        return resp

    # 平台随官方 App 更新抬高最低版本下限：低于下限时返回“请升级最新版app”。
    # 候选版本按顺序尝试，命中（code=0 或非版本类错误）即采用。
    VERSION_CANDIDATES = (
        "3.1.7", "3.1.8", "3.1.6", "3.1.5", "3.1.9",
        "3.1.10", "3.1.11", "3.1.12", "3.1.13", "3.1.14", "3.1.15",
        "3.2.0", "3.2.1", "3.2.2", "3.2.3", "3.2.4", "3.2.5", "3.2.6",
        "3.3.0", "3.3.1", "3.4.0", "3.5.0", "3.6.0", "3.8.0", "3.9.9",
        "4.0.0", "9.9.9",
    )

    @staticmethod
    def _is_version_issue(msg: str) -> bool:
        return ("升级" in (msg or "")) or ("版本" in (msg or ""))

    def resolve_version(self, emit: Callable[..., None]) -> Optional[Dict[str, Any]]:
        """探测一个被服务端接受的客户端版本号，命中后更新 self.version 并返回任务列表响应。"""
        seen = set()
        for v in (self.version,) + self.VERSION_CANDIDATES:
            if not v or v in seen:
                continue
            seen.add(v)
            self.version = v
            try:
                resp = self.get_mission_list()
            except ClaimNetworkError:
                continue
            if self.last_code == CODE_OK:
                emit("info", "客户端版本探测命中 %s" % v)
                return resp
            if not self._is_version_issue(self.last_msg):
                # 非版本类错误（登录过期、限流等），无需继续探测
                return None
        return None

    def get_score_list(self, page: int = 0, size: int = 200) -> Dict[str, Any]:
        """拉取积分明细，用于统计当天每个任务已经领了多少次。"""
        return self._call(
            "GET",
            "/api/v1/acc/score/score-lst?page=%d&size=%d&hasCount=true" % (page, size),
        )

    def today_done_counts(self) -> Dict[str, int]:
        """当天每个 adId 已完成的次数（与客户端 parseTodayDoneCount 一致）。"""
        try:
            resp = self.get_score_list()
        except ClaimNetworkError:
            return {}
        try:
            code = int(resp.get("code", -1))
        except Exception:
            code = -1
        if code != CODE_OK:
            return {}
        midnight = int(datetime.now(CN_TZ).replace(
            hour=0, minute=0, second=0, microsecond=0).timestamp()) * 1000
        out: Dict[str, int] = {}
        for item in (resp.get("data") or []):
            if not isinstance(item, dict):
                continue
            try:
                ctime = int(item.get("ctime") or 0)
            except Exception:
                continue
            if ctime < midnight:
                continue
            nested = item.get("data")
            ad_id = str(nested.get("adId") or "").strip() if isinstance(nested, dict) else ""
            if ad_id:
                out[ad_id] = out.get(ad_id, 0) + 1
        return out

    def send_score(self, ad_id: str, type_: Optional[int] = None,
                   week_day: Optional[int] = None) -> Dict[str, Any]:
        """提交一次积分领取 / 每日签到。

        与客户端逐字段一致：
          * 任务领取  body = { adId, type: 101 }
          * 每日签到  body = { adId, weekDay: 1..7 }  （大写 D）
        不要再附带 addScore / addScoreType / token —— 签到带上它们会判为无效请求。
        """
        sign = make_sign(ad_id, self.token, self.uid, self.local_ts, self.server_ts)
        body: Dict[str, Any] = {"adId": ad_id}
        if type_ is not None:
            body["type"] = int(type_)
        if week_day is not None:
            body["weekDay"] = int(week_day)
        return self._call("POST", "/api/v1/acc/score/score-send",
                          params={"sign": sign, "s": "true"}, body=body)

    # ------------------------------------------------------------ 领取引擎
    def claim_all(self, log: Callable[..., None], delay: float = 6.0,
                  max_per_task: int = 30, do_sign_in: bool = True) -> Dict[str, Any]:
        """把当天所有能领的积分领完。

        log(level, message, task=None, delta=0)
        level: info / success / warn / error
        返回 summary: claimed / points / score_before / score_after / tasks
        """
        summary: Dict[str, Any] = {
            "claimed": 0,
            "points": 0,
            "score_before": "0",
            "score_after": "0",
            "tasks": [],
            "error": None,
        }

        def emit(level: str, message: str, task: Optional[str] = None, delta: int = 0) -> None:
            try:
                log(level, message, task, delta)
            except TypeError:
                log(level, message)

        resp = self.get_mission_list()
        if self.last_code != CODE_OK and self._is_version_issue(self.last_msg):
            resolved = self.resolve_version(emit)
            if resolved is not None:
                resp = resolved
        if self.last_code == CODE_TOKEN_EXPIRED:
            raise TokenExpired(self.last_msg or "登录状态已过期")
        if self.last_code != CODE_OK:
            raise ClaimNetworkError("拉取任务列表失败: code=%s msg=%s"
                                    % (self.last_code, self.last_msg))

        data = resp.get("data") or {}
        if not isinstance(data, dict):
            raise ClaimNetworkError("任务列表返回结构异常")

        acc = data.get("accScoreRsp") or {}
        summary["score_before"] = str(acc.get("score", "0"))
        emit("info", "任务列表已获取，当前积分 %s" % summary["score_before"])

        # ---------- 1. 每日签到 ----------
        if do_sign_in:
            self._do_sign_in(data, acc, emit, delay)

        # ---------- 2. 第二套 token（小程序 / 官方 App）的任务 ----------
        # 客户端会分别用两个 token 拉任务列表再合并去重，服务端保持一致，
        # 否则只领到其中一套任务。
        app_client = self._app_client_for()
        app_missions: List[Dict[str, Any]] = []
        if app_client is not None:
            try:
                app_resp = app_client.get_mission_list()
                if app_client.last_code == CODE_OK:
                    app_data = app_resp.get("data") or {}
                    if isinstance(app_data, dict):
                        app_missions = app_data.get("missions") or []
                    emit("info", "第二套登录态任务数 %d，与主任务合并去重"
                         % len(app_missions))
                elif app_client.last_code == CODE_TOKEN_EXPIRED:
                    emit("warn", "第二套登录态已过期，本次只领取主任务",
                         "官方 App", 0)
                else:
                    emit("warn", "第二套登录态任务获取失败：%s"
                         % (app_client.last_msg or ("code=%d" % app_client.last_code)),
                         "官方 App", 0)
            except ClaimNetworkError as exc:
                emit("warn", "第二套登录态任务获取失败：%s" % exc, "官方 App", 0)

        merged = _merge_missions(data.get("missions") or [], app_missions)
        emit("info", "共 %d 个任务（主 %d + 官方 App %d，已去重），开始逐个领取"
             % (len(merged), len(data.get("missions") or []), len(app_missions)))

        # ---------- 3. 当天已领次数（客户端 parseTodayDoneCount 的等价实现）----------
        done_counts = self.today_done_counts()
        if done_counts:
            emit("info", "当天已有 %d 条领取记录，按剩余次数补领" % sum(done_counts.values()))

        # ---------- 4. 积分任务 ----------
        missions = merged

        for mission in missions:
            if not isinstance(mission, dict):
                continue
            ref_id = str(mission.get("refId") or "").strip()
            name = str(mission.get("name") or ref_id)
            platform = str(mission.get("__platform") or "main")
            mission_client = app_client if platform == "app" and app_client else self
            label = "[APP]" if mission_client is app_client and app_client is not None \
                else "[支付宝]"
            display = "%s %s" % (label, name)
            try:
                score = int(mission.get("score") or 0)
            except Exception:
                score = 0
            try:
                limit = int(mission.get("limit") or 0)
            except Exception:
                limit = 0
            if not ref_id:
                continue
            if score <= 0:
                emit("info", "跳过（积分为变量 / 需人工完成）", display, 0)
                continue
            if _should_skip_mission(name):
                emit("info", "跳过（客户端同样过滤该任务）", display, 0)
                continue
            if limit <= 0:
                emit("info", "跳过（没有每日次数上限）", display, 0)
                continue

            done = int(done_counts.get(ref_id, 0))
            remaining = limit - done
            if remaining <= 0:
                emit("info", "今天已领完（%d/%d）" % (done, limit), display, 0)
                continue
            cap = min(remaining, max(1, int(max_per_task)))

            claimed, last_code, last_msg = mission_client._claim_loop(
                ref_id, score, display, cap, delay, emit, type_=TYPE_SCORE_SEND,
            )

            summary["tasks"].append({
                "refId": ref_id, "name": name, "score": score,
                "claimed": claimed, "cap": cap, "platform": platform,
                "code": last_code, "msg": last_msg,
            })
            summary["claimed"] += claimed
            summary["points"] += claimed * score

            if last_code == CODE_TOKEN_EXPIRED:
                raise TokenExpired(last_msg or "登录状态已过期")
            # 客户端在每个任务之间固定等待 MISSION_GAP_MILLIS(30s)，服务端保持同样节奏。
            if claimed > 0 and float(delay) > 0:
                time.sleep(float(delay))

        # ---------- 5. 收尾核对 ----------
        try:
            final = self.get_mission_list()
            if self.last_code == CODE_OK:
                acc2 = (final.get("data") or {}).get("accScoreRsp") or {}
                summary["score_after"] = str(acc2.get("score", summary["score_before"]))
        except Exception:
            summary["score_after"] = "?"

        emit("success", "本次共领取 %d 次，积分 %s -> %s"
             % (summary["claimed"], summary["score_before"], summary["score_after"]))
        return summary

    # ------------------------------------------------------------ 内部实现
    def _claim_loop(self, ad_id: str, score: int, name: str, cap: int, delay: float,
                    emit: Callable[..., None],
                    type_: Optional[int] = None,
                    week_day: Optional[int] = None) -> Tuple[int, int, str]:
        claimed = 0
        attempts = 0
        rate_retries = 0
        last_code = 0
        last_msg = ""
        while attempts < cap:
            attempts += 1
            try:
                resp = self.send_score(ad_id, type_=type_, week_day=week_day)
            except ClaimNetworkError as exc:
                last_code = -1
                last_msg = str(exc)
                emit("error", "网络异常：%s" % exc, name, 0)
                break

            code = int(resp.get("code", -1))
            msg = str(resp.get("msg") or "")
            last_code, last_msg = code, msg

            if code == CODE_OK:
                claimed += 1
                rate_retries = 0
                emit("success", "+%d 积分（第 %d 次）" % (score, claimed), name, score)
                if claimed < cap and delay > 0:
                    time.sleep(delay)
                continue

            if code == CODE_RATE_LIMIT:
                if rate_retries >= 3:
                    emit("warn", "连续限流，放弃该任务（%s）" % msg, name, 0)
                    break
                rate_retries += 1
                attempts -= 1
                wait = max(0, int(self.rate_limit_wait))
                emit("warn", "触发限流，等待 %ds 后重试" % wait, name, 0)
                if wait:
                    time.sleep(wait)
                continue

            if code == CODE_TOKEN_EXPIRED:
                emit("error", "登录状态已过期，请重新登录后同步账号", name, 0)
                break

            emit("info", "该任务已领完：%s" % (msg or ("code=%d" % code)), name, 0)
            break
        return claimed, last_code, last_msg

    def _do_sign_in(self, data: Dict[str, Any], acc: Dict[str, Any],
                    emit: Callable[..., None], delay: float) -> None:
        daily_rsp = data.get("dailyRSP") or {}
        daily = acc.get("daily") or {}
        ad_id = str(daily_rsp.get("adId") or "").strip()
        # 与客户端 dailyRsp.optInt("score", 5) 对齐：字段缺失时按 5 处理。
        raw_score = daily_rsp.get("score")
        try:
            points = int(raw_score) if raw_score is not None else 5
        except Exception:
            points = 5
        week_mask = parse_week_mask(daily.get("week"))

        if not ad_id:
            emit("info", "没有签到任务", "每日签到", 0)
            return
        if signed_today(week_mask):
            emit("info", "今日已签到（week=%d）" % week_mask, "每日签到", 0)
            return

        # 客户端 DailySignInPlanner 只看 adId + 今日是否已签，不因积分为 0 而跳过，
        # 所以这里保持一致：score 仅用于日志展示。
        if points <= 0:
            emit("info", "签到未返回积分，仍按客户端逻辑提交", "每日签到", 0)

        claimed, code, msg = self._claim_loop(
            ad_id, points, "每日签到", 1, delay, emit,
            type_=None, week_day=today_weekday(),
        )
        if claimed == 0 and code not in (CODE_OK,):
            emit("warn", "签到未成功：%s" % (msg or ("code=%d" % code)), "每日签到", 0)

def run_account(account, log, delay=30.0, max_per_task=30):
    """给调度器/API 用的便捷入口：account 是 dict（token/uid/eid/phone）。

    delay 默认 30s，与客户端 MISSION_GAP_MILLIS 保持一致，避免触发 -98 限流。
    account 里的 app_token 是第二套登录态（小程序 / 官方 App），有值时两套任务会合并领取。
    """
    token = (account.get("token") or "").strip()
    app_token = (account.get("app_token") or "").strip()
    if not token:
        # 只有第二套 token 时退回单套模式，避免主链路直接失败。
        token, app_token = app_token, ""
    client = IlifeScoreClient(
        token=token,
        uid=account.get("uid", ""),
        eid=account.get("eid", "") or "",
        phone=account.get("phone", "") or "",
        host=account.get("host", "") or BASE_URL,
        app_token=app_token,
    )
    return client.claim_all(log, delay=delay, max_per_task=max_per_task)


if __name__ == "__main__":  # 手工自测：python ilife.py <token> <uid>
    import sys

    def _print(level, message, task=None, delta=0):
        print("[%s] %s %s" % (level, ("[%s] " % task) if task else "", message))

    if len(sys.argv) < 3:
        print("用法: python ilife.py <token> <uid>")
        sys.exit(1)
    print(json.dumps(run_account({"token": sys.argv[1], "uid": sys.argv[2]}, _print),
                     ensure_ascii=False, indent=2))
