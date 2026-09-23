# -*- coding: utf-8 -*-
"""离线自测：用合成 mission-lst 验证领取循环 / 签到 / 上限判定，不碰真实接口。"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ilife  # noqa: E402
from ilife import IlifeScoreClient  # noqa: E402

TODAY_BIT = 1 << (ilife.today_weekday() - 1)


def make_payload(limits=None, week=0, signed_points=10, missions=None):
    return {
        "code": 0,
        "time": ilife.now_ms(),
        "data": {
            "accScoreRsp": {
                "score": "100",
                "totalScore": "500",
                "validScore": "100",
                "limits": [{"refId": k, "limit": v} for k, v in (limits or {}).items()],
                "daily": {"ltime": 0, "reword": 0, "week": week},
            },
            "dailyRSP": {"adId": "signin_task", "score": signed_points,
                         "config": [{"day": 1, "score": signed_points}]},
            "lotteryEnable": False,
            "missions": missions or [],
        },
    }


class FakeClient(IlifeScoreClient):
    """把网络调用换成内存状态机。"""

    def __init__(self, payload, per_task_total, refuse_after=None,
                 app_payload=None, app_per_task_total=None, app_token="",
                 shared=None):
        super().__init__(token="t" * 20 + "abcdefgh", uid="u" * 20 + "12345678",
                         app_token=app_token)
        self.payload = payload
        self.per_task_total = dict(per_task_total)
        self.refuse_after = refuse_after or {}
        self.app_payload = app_payload
        self.app_per_task_total = dict(app_per_task_total or {})
        self._fake_app = None
        if shared is None:
            shared = {"sent": [], "done": {}}
        self.shared = shared
        self.sent = shared["sent"]
        self.done = shared["done"]
        self.local_ts = ilife.now_ms()
        self.server_ts = self.local_ts
        self.rate_limit_wait = 0

    def _app_client_for(self):
        """两套 token 的离线替身：共用一个 sent / done 便于断言。"""
        if not self.app_token or self.app_token == self.token or self.app_payload is None:
            return None
        if self._fake_app is None:
            self._fake_app = FakeClient(
                self.app_payload, self.app_per_task_total, shared=self.shared)
            self._fake_app.token = self.app_token
            self._fake_app.rate_limit_wait = self.rate_limit_wait
        return self._fake_app

    def get_mission_list(self):
        return self.payload

    def today_done_counts(self):
        return {k: v for k, v in self.done.items() if not k.startswith("__")}

    def send_score(self, ad_id, type_=None, week_day=None):
        self.sent.append((ad_id, type_, week_day, self.token))
        if ad_id == "signin_task" and type_ is None:
            if self.done.get("__signin"):
                return {"code": -1, "msg": "今日已签到"}
            self.done["__signin"] = 1
            return {"code": 0, "msg": "ok"}
        cap = self.per_task_total.get(ad_id, 1)
        used = self.done.get(ad_id, 0)
        if used >= cap:
            return {"code": -1, "msg": "已达上限"}
        self.done[ad_id] = used + 1
        return {"code": 0, "msg": "ok"}


def test_claim_until_cap():
    missions = [
        {"refId": "taskA", "name": "看视频A", "score": 5, "limit": 3},
        {"refId": "taskB", "name": "看视频B", "score": 10, "limit": 1},
        {"refId": "taskC", "name": "抽奖", "score": 0, "limit": 1},
    ]
    client = FakeClient(make_payload(week=0, missions=missions),
                        per_task_total={"taskA": 3, "taskB": 1})
    lines = []
    client.get_mission_list()
    summary = client.claim_all(lambda lv, m, t=None, d=0: lines.append((lv, t, m)),
                               delay=0, max_per_task=30)
    assert summary["claimed"] == 4, summary
    assert summary["points"] == 5 * 3 + 10, summary
    assert client.done.get("taskA") == 3 and client.done.get("taskB") == 1
    assert client.done.get("__signin") == 1, "签到应该被执行一次"
    print("test_claim_until_cap OK  claimed=%d points=%d" % (summary["claimed"], summary["points"]))


def test_signin_skipped_when_signed():
    missions = [{"refId": "taskA", "name": "看视频A", "score": 5, "limit": 1}]
    client = FakeClient(make_payload(week=TODAY_BIT, missions=missions),
                        per_task_total={"taskA": 1})
    client.get_mission_list()
    client.claim_all(lambda *a, **k: None, delay=0)
    assert client.done.get("__signin") is None, "今天已签到就不该再签"
    assert client.done.get("taskA") == 1
    print("test_signin_skipped_when_signed OK")


def test_limit_is_done_count_semantics():
    """即使 limits 返回的是“已完成次数”，也要能领满 mission.limit 次。"""
    missions = [{"refId": "taskA", "name": "看视频A", "score": 4, "limit": 5}]
    client = FakeClient(make_payload(week=TODAY_BIT, missions=missions),
                        per_task_total={"taskA": 5})
    client.get_mission_list()
    summary = client.claim_all(lambda *a, **k: None, delay=0)
    assert client.done.get("taskA") == 5, client.done
    assert summary["claimed"] == 5
    print("test_limit_is_done_count_semantics OK")


def test_rate_limit_retry():
    missions = [{"refId": "taskA", "name": "看视频A", "score": 3, "limit": 1}]
    client = FakeClient(make_payload(week=TODAY_BIT, missions=missions),
                        per_task_total={"taskA": 1})
    calls = {"n": 0}
    real = client.send_score

    def flaky(ad_id, type_=None, week_day=None):
        if ad_id == "taskA":
            calls["n"] += 1
            if calls["n"] == 1:
                return {"code": -98, "msg": "操作过于频繁"}
        return real(ad_id, type_, week_day)

    client.send_score = flaky
    client.get_mission_list()
    summary = client.claim_all(lambda *a, **k: None, delay=0)
    assert summary["claimed"] == 1 and calls["n"] >= 2, (summary, calls)
    print("test_rate_limit_retry OK")


def test_payload_matches_client_contract():
    """签到必须带 weekDay（大写 D）且不带 addScore；任务必须带 type=101。"""
    missions = [{"refId": "taskA", "name": "看视频A", "score": 5, "limit": 1}]
    client = FakeClient(make_payload(week=0, missions=missions),
                        per_task_total={"taskA": 1})
    client.get_mission_list()
    client.claim_all(lambda *a, **k: None, delay=0)

    signin = [s for s in client.sent if s[0] == "signin_task"]
    assert signin, client.sent
    assert signin[0][1] is None, "签到不能带 type"
    assert signin[0][2] == ilife.today_weekday(), ("签到必须带 weekDay", signin)

    task = [s for s in client.sent if s[0] == "taskA"]
    assert task and task[0][1] == 101 and task[0][2] is None, ("任务必须带 type=101", task)
    print("test_payload_matches_client_contract OK")


def test_missions_without_limit_are_skipped():
    """客户端会跳过没有每日上限 / 名称为借贷类的任务。"""
    missions = [
        {"refId": "taskA", "name": "看视频A", "score": 5, "limit": 0},
        {"refId": "taskB", "name": "借贷福利", "score": 9, "limit": 3},
        {"refId": "taskC", "name": "免费权益领取", "score": 9, "limit": 3},
    ]
    client = FakeClient(make_payload(week=TODAY_BIT, missions=missions),
                        per_task_total={"taskA": 5, "taskB": 3, "taskC": 3})
    client.get_mission_list()
    summary = client.claim_all(lambda *a, **k: None, delay=0)
    assert summary["claimed"] == 0, summary
    assert client.sent == [], client.sent
    print("test_missions_without_limit_are_skipped OK")


def test_signin_sent_when_score_missing():
    """客户端 dailyRsp.optInt("score", 5) 不因积分为 0 / 缺失而跳过签到。"""
    missions = [{"refId": "taskA", "name": "看视频A", "score": 5, "limit": 1}]
    payload = make_payload(week=0, missions=missions)
    payload["data"]["dailyRSP"].pop("score")
    client = FakeClient(payload, per_task_total={"taskA": 1})
    client.get_mission_list()
    client.claim_all(lambda *a, **k: None, delay=0)
    assert client.done.get("__signin") == 1, "缺少 score 时仍要签到"

    payload2 = make_payload(week=0, signed_points=0, missions=missions)
    client2 = FakeClient(payload2, per_task_total={"taskA": 1})
    client2.get_mission_list()
    client2.claim_all(lambda *a, **k: None, delay=0)
    assert client2.done.get("__signin") == 1, "score=0 时仍要签到"
    print("test_signin_sent_when_score_missing OK")


def test_second_token_tasks_are_merged():
    """两套 token（小程序 / 官方 App）的任务要合并去重，并各自用自己的 token 提交。"""
    main_missions = [
        {"refId": "taskA", "name": "支付宝任务", "score": 5, "limit": 1},
        {"refId": "taskShared", "name": "重复任务", "score": 5, "limit": 1},
    ]
    app_missions = [
        {"refId": "taskApp", "name": "官方 App 任务", "score": 8, "limit": 1},
        {"refId": "taskShared", "name": "重复任务", "score": 5, "limit": 1},
    ]
    client = FakeClient(
        make_payload(week=TODAY_BIT, missions=main_missions),
        per_task_total={"taskA": 1, "taskShared": 1},
        app_payload=make_payload(week=TODAY_BIT, missions=app_missions),
        app_per_task_total={"taskApp": 1, "taskShared": 1},
        app_token="a" * 20 + "ZZZZZZZZ",
    )
    client.get_mission_list()
    summary = client.claim_all(lambda *a, **k: None, delay=0)

    sent_main = [s for s in client.sent if s[3] == client.token]
    sent_app = [s for s in client.sent if s[3] == client.app_token]
    assert sorted(s[0] for s in sent_main) == ["taskA", "taskShared"], sent_main
    assert [s[0] for s in sent_app] == ["taskApp"], sent_app
    assert summary["claimed"] == 3, summary
    print("test_second_token_tasks_are_merged OK  claimed=%d" % summary["claimed"])


if __name__ == "__main__":
    test_claim_until_cap()
    test_signin_skipped_when_signed()
    test_limit_is_done_count_semantics()
    test_rate_limit_retry()
    test_payload_matches_client_contract()
    test_missions_without_limit_are_skipped()
    test_signin_sent_when_score_missing()
    test_second_token_tasks_are_merged()
    print("ALL TESTS PASSED")
