# 出水小部件 · 积分托管服务端（慧生活798）

App 端「我的 → 自动领取」把账号同步到这里，服务端每天定时把当天**所有能领的积分一次领完**，
把每一步写进日志；App 可以随时查看账号状态、运行记录和日志。

零第三方依赖，只要机器上有 Python 3.8+ 就能跑。

## 快速开始

```bat
cd server
python app.py
```

或直接双击 `start.bat`。启动后会打印：

```
 监听地址 : http://0.0.0.0:8787
 ApiKey   : xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
 每日执行 : 00:05 (Asia/Shanghai)
```

浏览器打开 `http://127.0.0.1:8787` 可以看到账号列表和最近日志。

然后把「监听地址」和「ApiKey」填进 App 的 **我的 → 自动领取** 页面即可。

## App 端怎么连

1. App 完成登录（设备登录 / 积分登录，和平时一样）。
2. 打开 **我的 → 自动领取**。
3. 填服务端地址，例如：
   - 手机和服务端在同一个 Wi-Fi：`http://192.168.1.10:8787`
   - 只有本机：`http://127.0.0.1:8787`（模拟器用 `http://10.0.2.2:8787`）
4. 填 ApiKey，点「保存并检测」。
5. 点「同步账号」——App 会把本机所有带积分登录态的账号（token / uid / eid / 手机号）推给服务端。
6. 点「立即领取全部」可以马上验证，之后每天到点自动跑。
7. 「日志」页签实时查看每一步的领取日志，可按账号、按级别过滤。

> App 每次回到前台都会在后台静默再同步一次，
> 所以 token 刷新后不用手动重同步，服务端拿到的始终是最新凭证。

## 定时任务

内置调度器：**每天 00:05（北京时间）** 自动跑全部账号。

也可以用系统计划任务（适合服务端不常开的情况）：

```bat
schtasks /create /tn "WaterPoints" /tr "python C:\path\to\server\app.py --once" /sc daily /st 00:05
```

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ILIFE_PORT` | 4848 | HTTP 端口 |
| `ILIFE_HOST` | 0.0.0.0 | 监听地址 |
| `ILIFE_API_KEY` | 自动生成 | 固定 ApiKey（不设则写入 `data/api_key.txt`） |
| `ILIFE_RUN_HOUR` / `ILIFE_RUN_MINUTE` | 0 / 5 | 每日执行时间（北京时间） |
| `ILIFE_CLAIM_DELAY` | 30 | 任务之间等待的秒数；客户端固定 30s，改小容易触发 -98 |
| `ILIFE_MAX_PER_TASK` | 30 | 单个任务每轮最多领取次数上限 |
| `ILIFE_LOG_DAYS` | 30 | 日志保留天数 |
| `ILIFE_DATA_DIR` | `./data` | 数据目录（SQLite + ApiKey） |

## HTTP 接口

所有接口都要带 `X-Api-Key` 头（或 `?key=`）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查 |
| GET | `/api/accounts` | 账号列表（token 已脱敏） |
| POST | `/api/accounts` | 同步账号 `{phone,token,app_token,uid,eid,name,host}`，`uid` 必填，`token`/`app_token` 至少一个 |
| DELETE | `/api/accounts/{id}` | 删除账号 |
| POST | `/api/accounts/{id}/run` | 立即领取该账号 |
| POST | `/api/run` | 立即领取全部账号 |
| GET | `/api/runs?account_id=&limit=` | 运行记录 |
| GET | `/api/logs?account_id=&limit=&level=&since_id=` | 领取日志 |

## 领取逻辑

服务端逐字段复刻 App 客户端（`TaskForegroundService` / `IlifeApi`）的领取流程：

0. **两套登录态**：主 token（支付宝 / 小程序，`token`）与官方 App token（`app_token`）。
   客户端就是这么干的——两个 token 分别拉任务目录再合并去重，所以服务端也存两份、
   合并领取；只有一套 token 时自动退化为单套模式；
1. `GET /api/v1/acc/score/mission-lst`（两个 token 都用 `ApplicationType: 1,5`，客户端如此）
   → `accScoreRsp.daily.week`、`dailyRSP`、`missions[]`；两份任务按 `refId` 去重（主平台优先）；
2. **每日签到**：`accScoreRsp.daily.week` 的当天位没置位时提交一次
   `POST /api/v1/acc/score/score-send?sign=<md5>&s=true`，body `{adId, weekDay}`；
   与客户端 `DailySignInPlanner` 一致，只要 `dailyRSP.adId` 存在且今天没签就提交，
   不因 `score` 缺失或为 0 而跳过（`score` 只用于日志展示）；
3. `GET /api/v1/acc/score/score-lst?page=0&size=200&hasCount=true` 统计**当天**
   已完成的次数（`ctime >= 本地 0 点` 且 `data.adId` 匹配），得到每个任务的剩余次数；
4. 逐个任务 `POST .../score-send?sign=<md5>&s=true`，body `{adId, type: 101}`，
   每个任务用**自己那套 token** 签名提交（App 任务用 `app_token`，其余用 `token`），
   循环领取到 `mission.limit` 用满为止；`免费权益 / 借贷 / 贷款` 类任务与客户端一样跳过；
5. 遇到 `-98`（限流）等 60s 重试，最多 3 次；遇到 `-99`（登录态失效）抛出并提示重新登录。

> 签到请求体必须是 `{adId, weekDay}`（**大写 D**，来自官方 `PointsAdInfo`）。
> 早期版本发的是小写 `weekday` 并附带 `addScoreType/addScore/token`，服务端会判为
> 无效请求，所以客户端能签到而服务端不能。

签名算法：`md5(adId + str(v20) + token[-8:] + uid[-8:] + SALT)`，
其中 `v20 = 10 * ((serverTs - localTs + nowMs) // 10000)`。
SALT 通过环境变量 `ILIFE_SIGN_SALT` 覆盖（默认与 App 端 `SIGN_SALT` 一致）。

## 自测

```bat
python selftest.py
```

用合成 mission-lst 验证领取循环 / 签到 / 上限判定，不碰真实接口。

## 注意事项

* token 失效（App 退出登录 / 服务端判定过期）后，需要**重新登录 App 并再次同步账号**。
* 领取间隔默认 6 秒，别调太小，容易触发 `-98` 限流。
* 账号多时服务端默认串行执行，避免风控。
* 为兼容局域网明文 HTTP，App 已放开 cleartext；官方接口仍走 HTTPS。
