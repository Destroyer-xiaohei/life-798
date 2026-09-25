package com.water.widget;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Random;

/**
 * 服务端 API 封装。
 *
 * 统一请求头：
 *   Content-Type: application/json
 *   ApplicationType: 1,5 或 1,1
 *   Authorization: <token>
 */
public class IlifeApi {
    static final String GATEWAY = BuildConfig.API_GATEWAY;
    static final String CID = BuildConfig.API_CID;
    private static final String SIGN_SALT = BuildConfig.SIGN_SALT;
    // 服务端按客户端版本号做最低版本校验，过低会返回“请升级最新版app”。
    // 平台会随官方 App 更新抬高下限，因此这里运行时可调，并通过 ensureClientVersion 自动探测
    // 一个被服务端接受的版本（探测结果会持久化）。
    public static final String DEFAULT_CLIENT_VERSION = "3.1.7";
    private static volatile String clientVersion = DEFAULT_CLIENT_VERSION;

    public static String getClientVersion() {
        return clientVersion;
    }

    public static void setClientVersion(String version) {
        if (version != null && !version.trim().isEmpty()) {
            clientVersion = version.trim();
        }
    }

    private static String ua() {
        return "Android_ilife798_" + clientVersion;
    }
    private static final String DEVICE_LOGIN_REJECTED_MESSAGE =
            "设备登录信息未被接受，请检查是否填反或重新完成设备登录";

    public interface ImgCallback {
        void onResult(byte[] bytes, String err);
    }

    public interface JsonCallback {
        void onResult(JSONObject json, String err);
    }

    public interface TextCallback {
        void onResult(String text, String err);
    }

    public interface VersionCallback {
        void onResult(String version, String err);
    }

    // ====== 积分通道 ======

    // 积分接口可能要求特定的 token + ApplicationType 组合：官方 App 用「设备登录 token + 1,1」，
    // 而旧客户端用「积分 token + 1,5」。进入前台时自动探测一组可用组合并持久化。
    private static volatile String scorePointsToken = "";
    private static volatile String scoreAppToken = "";
    private static volatile boolean scoreUseAppToken = false;
    private static volatile String scoreAppType = "1,5";

    public static String getScoreAppType() {
        return scoreAppType;
    }

    public static boolean isScoreUseAppToken() {
        return scoreUseAppToken;
    }

    public static void setScoreTokens(String pointsToken, String appToken) {
        scorePointsToken = pointsToken == null ? "" : pointsToken;
        scoreAppToken = appToken == null ? "" : appToken;
    }

    public static void applyScoreChannel(String appType, boolean useAppToken) {
        if (appType != null && !appType.isEmpty()) {
            scoreAppType = appType;
        }
        scoreUseAppToken = useAppToken;
    }

    private static String effectiveScoreToken(String passed) {
        if (scoreUseAppToken && !scoreAppToken.isEmpty()) return scoreAppToken;
        if (!scorePointsToken.isEmpty()) return scorePointsToken;
        return passed == null ? "" : passed;
    }

    /**
     * 探测积分接口可用的 token / ApplicationType / 版本组合，命中后写入
     * {@link #applyScoreChannel} 与 {@link #setClientVersion}。
     * 组合顺序：积分token+1,5（旧行为）→ 设备token+1,1（官方行为）→ 交叉。
     */
    public static void detectScoreChannel(final String pointsToken, final String appToken,
                                          final VersionCallback cb) {
        setScoreTokens(pointsToken, appToken);
        new Thread(() -> {
            String[][] combos = {
                    {pointsToken, "1,5", "0"},
                    {appToken, "1,1", "1"},
                    {pointsToken, "1,1", "0"},
                    {appToken, "1,5", "1"},
            };
            String[] versions = {clientVersion, "3.1.7", "3.1.9", "3.1.10", "3.2.0"};
            for (String[] combo : combos) {
                String tk = combo[0];
                String at = combo[1];
                boolean useApp = "1".equals(combo[2]);
                if (tk == null || tk.isEmpty()) continue;
                for (String v : versions) {
                    try {
                        String body = httpRawApp(
                                "GET", GATEWAY + "/acc/score/mission-lst", null, tk, at, v);
                        JSONObject json = new JSONObject(body);
                        int code = json.optInt("code", -999);
                        String msg = json.optString("msg", "");
                        if (code == 0) {
                            applyScoreChannel(at, useApp);
                            setClientVersion(v);
                            cb.onResult(v, null);
                            return;
                        }
                        // 登录失效：该 token 不可用，换下一组
                        if (code == -99) continue;
                        boolean versionIssue = msg.contains("升级") || msg.contains("版本");
                        if (!versionIssue) {
                            // 其它业务错误（限流等）说明通道本身可用
                            applyScoreChannel(at, useApp);
                            setClientVersion(v);
                            cb.onResult(v, null);
                            return;
                        }
                    } catch (Exception e) {
                        // 网络异常，尝试下一组
                    }
                }
            }
            cb.onResult(null, "未找到可用积分通道");
        }).start();
    }

    // ====== 版本探测 ======

    /**
     * 平台会随官方 App 更新抬高最低版本下限，过低时所有积分接口都返回“请升级最新版app”。
     * 这里用只读的 mission-lst 逐个尝试候选版本号，命中后写入 {@link #setClientVersion}。
     * 命中判定：code==0，或错误信息与“版本/升级”无关（例如登录过期、限流）。
     */
    public static void ensureClientVersion(final String token, final VersionCallback cb) {
        new Thread(() -> {
            if (token == null || token.isEmpty()) {
                cb.onResult(clientVersion, null);
                return;
            }
            // 官方客户端上报的是 versionName（当前为 3.1.7），平台要求与官方最新版一致；
            // 先试当前值，再按已知官方版本与邻近版本逐个尝试。
            String[] candidates = {
                    clientVersion,
                    "3.1.7", "3.1.8", "3.1.6", "3.1.5", "3.1.9",
                    "3.1.10", "3.1.11", "3.1.12", "3.1.13", "3.1.14", "3.1.15",
                    "3.2.0", "3.2.1", "3.2.2", "3.2.3", "3.2.4", "3.2.5", "3.2.6",
                    "3.3.0", "3.3.1", "3.4.0", "3.5.0", "3.6.0", "3.8.0", "3.9.9",
                    "4.0.0", "9.9.9"
            };
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (String v : candidates) {
                if (v == null || v.isEmpty() || !seen.add(v)) continue;
                try {
                    String body = httpRawApp(
                            "GET", GATEWAY + "/acc/score/mission-lst", null, token, "1,5", v);
                    JSONObject json = new JSONObject(body);
                    int code = json.optInt("code", -999);
                    String msg = json.optString("msg", "");
                    boolean versionIssue = msg.contains("升级") || msg.contains("版本");
                    if (code == 0 || !versionIssue) {
                        setClientVersion(v);
                        cb.onResult(v, null);
                        return;
                    }
                } catch (Exception e) {
                    // 网络异常：保留当前版本，交由上层提示
                    cb.onResult(clientVersion, e.getMessage());
                    return;
                }
            }
            cb.onResult(null, "未找到被服务端接受的客户端版本");
        }).start();
    }

    // ====== 签名 ======

    /**
     * 积分任务签名。
     * 实际算法通过 BuildConfig 注入的参数完成，源码中不含具体实现。
     * 本地构建时需在项目根目录创建 secrets.properties 并填入：
     *   API_GATEWAY=https://example.com/api/v1
     *   SIGN_SALT=your_salt_here
     *   API_CID=your_cid_here
     */
    public static String sign(String adId, String token, String uid) {
        if (SIGN_SALT == null || SIGN_SALT.isEmpty()) {
            return "";
        }
        return Signer.sign(adId, token, uid, SIGN_SALT);
    }

    // ====== 登录流程 ======

    public static String newCaptchaKey() {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < 10; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    public static void captcha(final String s, final ImgCallback cb) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                String u = GATEWAY + "/captcha/?s=" + URLEncoder.encode(s, "UTF-8")
                        + "&r=" + System.currentTimeMillis();
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent", ua());
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                InputStream is = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                cb.onResult(out.toByteArray(), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    public static void sendSms(final String phone, final String graphCode, final String s,
                               final JsonCallback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("un", phone);
            body.put("authCode", graphCode);
            body.put("s", s);
        } catch (Exception ignored) {}
        post("/acc/login/code", body, null, cb);
    }

    public static void login(final String phone, final String smsCode, final JsonCallback cb) {
        login(phone, smsCode, false, cb);
    }

    /**
     * 登录。
     * @param asApp true=用设备控制方式登录（ApplicationType=1,1）
     *              false=用账户服务方式登录（ApplicationType=1,5）
     */
    public static void login(final String phone, final String smsCode,
                             final boolean asApp, final JsonCallback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("openCode", "");
            body.put("authCode", smsCode);
            body.put("un", phone);
            // 官方客户端登录体只含 authCode/openCode/un；cid 属白标渠道参数，配置后才附带。
            if (CID != null && !CID.isEmpty()) {
                body.put("cid", CID);
            }
        } catch (Exception ignored) {}
        final String appType = asApp ? "1,1" : "1,5";
        new Thread(() -> {
            try {
                String resp = httpRawApp("POST", GATEWAY + "/acc/login",
                        body.toString(), null, appType);
                cb.onResult(new JSONObject(resp), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    // ====== 账户信息 ======

    /** 取账户信息，含 uid（id 字段）。用于补全 Account.uid。 */
    public static void viewInfo(Context ctx, final JsonCallback cb) {
        get(ctx, "/acc/view-info", cb);
    }

    // ====== 积分任务 ======

    public static void missionLst(Context ctx, final JsonCallback cb) {
        get(ctx, "/acc/score/mission-lst", cb);
    }

    /** 用指定 token 获取任务列表（用于双平台合并）。 */
    public static void missionLstWithToken(final String token, final JsonCallback cb) {
        new Thread(() -> {
            try {
                String body = httpRawApp("GET", GATEWAY + "/acc/score/mission-lst", null,
                        effectiveScoreToken(token), scoreAppType);
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 获取积分明细（用于校验任务是否已完成）。 */
    public static void scoreLst(final Context ctx, final JsonCallback cb) {
        get(ctx, "/acc/score/score-lst?page=0&size=200&hasCount=1", cb);
    }

    /** 用指定 token 获取账户信息（uid/eid/手机号）。 */
    public static void viewInfoWithToken(final String token, final JsonCallback cb) {
        new Thread(() -> {
            try {
                String body = httpRaw("GET", GATEWAY + "/acc/view-info", null, token);
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /**
     * 只读探测一条手动导入的登录信息。
     * 同时检查两种请求头下的账户信息、任务目录和设备首页，供本地判断登录平台。
     */
    public static void probeToken(final String token, final JsonCallback cb) {
        new Thread(() -> {
            JSONObject result = new JSONObject();
            String firstError = null;
            String[][] requests = new String[][] {
                    {"viewMain", "/acc/view-info", "1,5"},
                    {"viewApp", "/acc/view-info", "1,1"},
                    {"missionMain", "/acc/score/mission-lst", "1,5"},
                    {"masterApp", "/ui/app/master", "1,1"}
            };
            for (String[] request : requests) {
                try {
                    String body = httpRawApp("GET", GATEWAY + request[1], null, token, request[2]);
                    result.put(request[0], new JSONObject(body));
                } catch (Exception e) {
                    if (firstError == null) firstError = e.getMessage();
                }
            }
            cb.onResult(result, firstError);
        }).start();
    }

    /** 用指定 token 获取积分明细。 */
    public static void scoreLstWithToken(final String token, final JsonCallback cb) {
        new Thread(() -> {
            try {
                String body = httpRawApp("GET",
                        GATEWAY + "/acc/score/score-lst?page=0&size=200&hasCount=1", null,
                        effectiveScoreToken(token), scoreAppType);
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 用设备控制登录信息获取最近账单；type=91 为设备消费。 */
    public static void billListWithToken(final String token, final JsonCallback cb) {
        new Thread(() -> {
            try {
                String body = httpRawApp(
                        "GET",
                        GATEWAY + "/bill/lst-owner?page=0&size=20",
                        null,
                        token,
                        "1,1"
                );
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /**
     * 用设备控制登录信息分页获取账单记录。
     * status：1 未付款 / 3 已付款 / 2 待确认 / 4 付款失败 / 9 已取消。
     */
    public static void billListWithToken(final String appToken, final int page, final int size,
                                         final int status, final JsonCallback cb) {
        String path = "/bill/lst-owner?page=" + page
                + "&size=" + size
                + "&hasCount=" + (page == 0)
                + "&status=" + status;
        requestApp("GET", path, null, appToken, cb);
    }

    /** 获取账单详情（bill/view-full：data.bill + data.cnt）。 */
    public static void billDetailWithToken(final String appToken, final String billId,
                                           final JsonCallback cb) {
        requestApp("GET", "/bill/view-full?id=" + enc(billId), null, appToken, cb);
    }

    /** 钱包退款：POST /acc/wallet/refund，type=23（支付宝小程序，与官方一致）。 */
    public static void refundWalletWithToken(final String appToken, final String eid,
                                             final JsonCallback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("eid", eid);
            body.put("type", 23);
        } catch (JSONException e) {
            throw new IllegalStateException("无法创建退款请求", e);
        }
        requestApp("POST", "/acc/wallet/refund", body, appToken, cb);
    }

    /** 使用设备控制登录信息获取账户钱包。 */
    public static void walletOwnerWithToken(final String appToken, final JsonCallback cb) {
        requestApp("GET", "/acc/wallet/owner?all=true", null, appToken, cb);
    }

    /** 使用设备登录信息读取可用积分。 */
    public static void accountScoreWithToken(final String appToken, final JsonCallback cb) {
        requestApp("GET", "/acc/score/acc-score", null, appToken, cb);
    }

    /** 兑换仅提交一次；网络结果不明确时由调用方核验，不自动重试。 */
    public static void exchangeScoreWithToken(final String appToken, final String endpointId,
                                              final int score, final JsonCallback cb) {
        requestApp("POST", "/acc/score/score-use",
                ScoreExchangeParser.INSTANCE.requestBody(endpointId, score), appToken, cb);
    }

    public static void exchangeBillWithToken(final String appToken, final String billId,
                                             final JsonCallback cb) {
        requestApp("GET", "/bill/view-full?id=" + enc(billId), null, appToken, cb);
    }

    /** 获取指定钱包端点的充值产品。 */
    public static void rechargeProductsWithToken(final String appToken,
                                                 final String endpointId,
                                                 final JsonCallback cb) {
        String path = "/prd/lst?eid=" + enc(endpointId)
                + "&type=1&status=1&all=false&did=&page=0&size=100&hasCount=false";
        requestApp("GET", path, null, appToken, cb);
    }

    /** 为指定钱包和充值产品创建订单。 */
    public static void createRechargeOrderWithToken(final String appToken,
                                                    final String endpointId,
                                                    final String ownerId,
                                                    final String productId,
                                                    final JsonCallback cb) {
        requestApp(
                "POST",
                "/bill/save",
                rechargeOrderBody(endpointId, ownerId, productId),
                appToken,
                cb
        );
    }

    /** 获取支付宝 SDK 所需的支付字符串。 */
    public static void prepayAlipayWithToken(final String appToken,
                                             final String orderId,
                                             final JsonCallback cb) {
        requestApp("GET", "/trans/prepay/21?id=" + enc(orderId), null, appToken, cb);
    }

    static JSONObject rechargeOrderBody(String endpointId, String ownerId, String productId) {
        try {
            return new JSONObject()
                    .put("cata", 1)
                    .put("contact", new JSONObject().put("id", ownerId))
                    .put("ep", new JSONObject().put("id", endpointId))
                    .put("note", "钱包充值")
                    .put("owner", new JSONObject().put("id", ownerId))
                    .put("prds", new org.json.JSONArray().put(
                            new JSONObject().put("id", productId).put("count", 1)
                    ));
        } catch (JSONException e) {
            throw new IllegalStateException("无法创建充值订单请求", e);
        }
    }

    /** 用指定 token 执行积分任务（用于双平台合并）。 */
    public static void scoreSendWithToken(final String token, final String uid,
                                          final String adId, final JsonCallback cb) {
        new Thread(() -> {
            try {
                final String tk = effectiveScoreToken(token);
                String effectiveUid = uid;
                if (effectiveUid == null || effectiveUid.isEmpty()) {
                    effectiveUid = fetchUidSync(tk);
                }
                final String sg = sign(adId, tk, effectiveUid != null ? effectiveUid : "");
                String url = GATEWAY + "/acc/score/score-send?sign=" + sg + "&s=true";
                JSONObject body = new JSONObject();
                body.put("adId", adId);
                body.put("type", 101);
                String resp = httpRawApp("POST", url, body.toString(), tk, scoreAppType);
                cb.onResult(new JSONObject(resp), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 每日签到。weekDay: 1=周一...7=周日, signAdId: 签到adId(通常为"DAILY_CHECK_IN")。 */
    public static void scoreSendSignIn(final String token, final String uid,
                                       final int weekDay, final String signAdId,
                                       final JsonCallback cb) {
        new Thread(() -> {
            try {
                final String tk = effectiveScoreToken(token);
                String effectiveUid = uid;
                if (effectiveUid == null || effectiveUid.isEmpty()) {
                    effectiveUid = fetchUidSync(tk);
                }
                final String sg = sign(signAdId, tk, effectiveUid != null ? effectiveUid : "");
                String url = GATEWAY + "/acc/score/score-send?sign=" + sg + "&s=true";
                JSONObject body = new JSONObject();
                body.put("weekDay", weekDay);
                body.put("adId", signAdId);
                String resp = httpRawApp("POST", url, body.toString(), tk, scoreAppType);
                cb.onResult(new JSONObject(resp), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /**
     * 完成任务（带签名）。
     * POST /acc/score/score-send?sign=<sign>&s=true  body={adId, type:101}
     */
    public static void scoreSend(final Context ctx, final String adId, final JsonCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            if (acc == null || !acc.hasToken()) {
                cb.onResult(null, "未登录");
                return;
            }
            // 确保 uid 存在
            String uid = acc.uid;
            if (uid == null || uid.isEmpty()) {
                // 同步取 uid
                uid = fetchUidSync(acc.token);
                if (uid != null && !uid.isEmpty()) {
                    acc.uid = uid;
                    AccountStore.updateCurrent(ctx, acc);
                }
            }
            final String sg = sign(adId, acc.token, uid != null ? uid : "");
            String url = GATEWAY + "/acc/score/score-send?sign=" + sg + "&s=true";
            JSONObject body = new JSONObject();
            try {
                body.put("adId", adId);
                body.put("type", 101);
            } catch (Exception ignored) {}
            try {
                String resp = httpRaw("POST", url, body.toString(), acc.token);
                cb.onResult(new JSONObject(resp), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 同步获取 uid（从 view-info）。 */
    private static String fetchUidSync(String token) {
        try {
            String resp = httpRaw("GET", GATEWAY + "/acc/view-info", null, token);
            JSONObject json = new JSONObject(resp);
            if (json.optInt("code", -999) == 0) {
                JSONObject data = json.optJSONObject("data");
                if (data != null) return data.optString("id", "");
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ====== 设备 ======

    public static void master(Context ctx, final JsonCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            boolean useApp = acc != null && acc.hasAppToken();
            String token = acc == null ? "" : (useApp ? acc.appToken : acc.token);
            String appType = useApp ? "1,1" : "1,5";
            try {
                String body = httpRawApp("GET", GATEWAY + "/ui/app/master", null, token, appType);
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    public static void devStart(Context ctx, final String did, final TextCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            if (acc == null || (!acc.hasToken() && !acc.hasAppToken())) {
                cb.onResult(null, "未登录");
                return;
            }
            // 设备控制需要使用设备控制登录信息（ApplicationType=1,1）。
            // 账户服务登录信息（1,5）无法用于设备控制。
            boolean useApp = acc.hasAppToken();
            String token = useApp ? acc.appToken : acc.token;
            String appType = useApp ? "1,1" : "1,5";
            String url = GATEWAY + "/dev/start?did=" + enc(did)
                    + "&upgrade=true&ptype=91&rcp=false";
            try {
                String body = httpRawApp("GET", url, null, token, appType);
                JSONObject json = new JSONObject(body);
                int code = json.optInt("code", -999);
                String msg = json.optString("msg", "");
                if (code == 0) {
                    cb.onResult("成功 ✓", null);
                } else if (code == -99) {
                    cb.onResult(null, "TOKEN_EXPIRED");
                } else if (code == -21) {
                    cb.onResult(null, useApp ? DEVICE_LOGIN_REJECTED_MESSAGE : "需要设备控制登录信息，请先在账户中添加");
                } else if (code == -88) {
                    cb.onResult(null, "未签约代扣协议，请在服务端完成签约后再使用");
                } else if (code == -87) {
                    cb.onResult(null, "签约已过期，请重新签约");
                } else if (code == -52) {
                    cb.onResult(null, "账户欠费，请充值后使用");
                } else if (code == -20) {
                    cb.onResult(null, "未绑定一卡通账号，请先完成账户绑定");
                } else if (code == -19) {
                    cb.onResult(null, "设备准备中，请稍后");
                } else if (code == -82) {
                    cb.onResult(null, "需要支付");
                } else {
                    cb.onResult(null, msg.isEmpty() ? "code=" + code : msg);
                }
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /**
     * 使用调用时冻结的设备控制 Token 启动设备，避免异步请求期间切换当前账户后串用凭据。
     */
    public static void devStartWithToken(
            final String appToken,
            final String did,
            final TextCallback cb
    ) {
        devStartWithToken(appToken, did, 91, "", cb);
    }

    /**
     * 使用指定支付方式启动设备。
     * ptype：91 = 商家钱包，21 = 支付宝免密；args 为设备启动参数（可为空）。
     */
    public static void devStartWithToken(
            final String appToken,
            final String did,
            final int ptype,
            final String args,
            final TextCallback cb
    ) {
        new Thread(() -> {
            if (appToken == null || appToken.isEmpty()) {
                cb.onResult(null, "需要设备控制登录信息，请先在账户中添加");
                return;
            }
            String url = GATEWAY + "/dev/start?did=" + enc(did)
                    + "&upgrade=true&ptype=" + ptype + "&rcp=false&cnt=1"
                    + "&args=" + enc(args == null ? "" : args);
            try {
                String body = httpRawApp("GET", url, null, appToken, "1,1");
                JSONObject json = new JSONObject(body);
                int code = json.optInt("code", -999);
                String msg = json.optString("msg", "");
                if (code == 0) {
                    cb.onResult("成功 ✓", null);
                } else if (code == -99) {
                    cb.onResult(null, "TOKEN_EXPIRED");
                } else if (code == -21) {
                    cb.onResult(null, DEVICE_LOGIN_REJECTED_MESSAGE);
                } else if (code == -88) {
                    cb.onResult(null, "未签约代扣协议，请在服务端完成签约后再使用");
                } else if (code == -87) {
                    cb.onResult(null, "签约已过期，请重新签约");
                } else if (code == -52) {
                    cb.onResult(null, "账户欠费，请充值后使用");
                } else if (code == -20) {
                    cb.onResult(null, "未绑定一卡通账号，请先完成账户绑定");
                } else if (code == -19) {
                    cb.onResult(null, "设备准备中，请稍后");
                } else if (code == -82) {
                    cb.onResult(null, "需要支付");
                } else {
                    cb.onResult(null, msg.isEmpty() ? "code=" + code : msg);
                }
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 结束出水。 */
    public static void devEnd(Context ctx, final String did, final TextCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            if (acc == null || (!acc.hasToken() && !acc.hasAppToken())) {
                cb.onResult(null, "未登录");
                return;
            }
            boolean useApp = acc.hasAppToken();
            String token = useApp ? acc.appToken : acc.token;
            String appType = useApp ? "1,1" : "1,5";
            devEndRequest(token, appType, did, cb);
        }).start();
    }

    /**
     * 使用调用时冻结的设备控制 Token 停止设备，避免异步请求期间切换当前账户后串用凭据。
     */
    public static void devEndWithToken(
            final String appToken,
            final String did,
            final TextCallback cb
    ) {
        new Thread(() -> {
            if (appToken == null || appToken.isEmpty()) {
                cb.onResult(null, "需要设备控制登录信息，请先在账户中添加");
                return;
            }
            devEndRequest(appToken, "1,1", did, cb);
        }).start();
    }

    /** 停止出水的实际请求；错误码统一由 deviceErrorMessage 转换。 */
    private static void devEndRequest(String token, String appType, String did,
                                      TextCallback cb) {
        String url = GATEWAY + "/dev/end?did=" + enc(did) + "&rcp=false";
        try {
            String body = httpRawApp("GET", url, null, token, appType);
            JSONObject json = new JSONObject(body);
            int code = json.optInt("code", -999);
            String msg = json.optString("msg", "");
            if (code == 0) {
                cb.onResult("已停止 ✓", null);
            } else {
                cb.onResult(null, deviceErrorMessage(code, msg));
            }
        } catch (Exception e) {
            cb.onResult(null, e.getMessage());
        }
    }

    /**
     * 查询设备当前状态（设备控制接口，ApplicationType=1,1）。
     * GET /ui/app/dev/status?did=&lt;did&gt;&more=false
     * 返回 data.device.gene.status：1 = 正在出水，99 = 空闲。
     */
    public static void devStatusWithToken(final String appToken, final String did,
                                          final JsonCallback cb) {
        new Thread(() -> {
            if (appToken == null || appToken.isEmpty()) {
                cb.onResult(null, "需要设备控制登录信息，请先在账户中添加");
                return;
            }
            try {
                String url = GATEWAY + "/ui/app/dev/status?did=" + enc(did) + "&more=false";
                String body = httpRawApp("GET", url, null, appToken, "1,1");
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 用当前账户的设备控制登录信息查询设备状态。 */
    public static void devStatus(Context ctx, final String did, final JsonCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            if (acc == null || !acc.hasAppToken()) {
                cb.onResult(null, "需要设备控制登录信息，请先在账户中添加");
                return;
            }
            devStatusWithToken(acc.appToken, did, cb);
        }).start();
    }

    /** dev/status 里设备是否正在出水（gene.status == 1）。 */
    public static boolean isDispensing(JSONObject json) {
        if (json == null) return false;
        JSONObject data = json.optJSONObject("data");
        JSONObject device = data != null ? data.optJSONObject("device") : null;
        JSONObject gene = device != null ? device.optJSONObject("gene") : null;
        return gene != null && gene.optInt("status", 99) == 1;
    }

    /** 设备控制接口错误码转可读提示；-99 保留为 TOKEN_EXPIRED 供调用方识别。 */
    private static String deviceErrorMessage(int code, String msg) {
        switch (code) {
            case -99: return "TOKEN_EXPIRED";
            case -21: return DEVICE_LOGIN_REJECTED_MESSAGE;
            case -88: return "未签约代扣协议，请在服务端完成签约后再使用";
            case -87: return "签约已过期，请重新签约";
            case -52: return "账户欠费，请充值后使用";
            case -20: return "未绑定一卡通账号，请先完成账户绑定";
            case -19: return "设备准备中，请稍后";
            case -82: return "需要支付";
            default: return msg == null || msg.isEmpty() ? "code=" + code : msg;
        }
    }

    /** 添加或移除账户收藏设备。remove=true 表示移除。 */
    public static void deviceFavorite(Context ctx, final String did, final boolean remove,
                                      final TextCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            if (acc == null || (!acc.hasToken() && !acc.hasAppToken())) {
                cb.onResult(null, "未登录");
                return;
            }
            boolean useApp = acc.hasAppToken();
            String token = useApp ? acc.appToken : acc.token;
            String appType = useApp ? "1,1" : "1,5";
            String url = GATEWAY + "/dev/favo?did=" + enc(did) + "&remove=" + (remove ? "1" : "0");
            try {
                JSONObject json = new JSONObject(httpRawApp("GET", url, null, token, appType));
                int code = json.optInt("code", -999);
                if (code == 0) {
                    cb.onResult(remove ? "已移除" : "已添加", null);
                } else if (code == -99) {
                    cb.onResult(null, "TOKEN_EXPIRED");
                } else {
                    String msg = json.optString("msg", "");
                    cb.onResult(null, msg.isEmpty() ? "code=" + code : msg);
                }
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    // ====== 通用请求 ======

    private static void get(Context ctx, final String path, final JsonCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            String token = acc != null ? acc.token : "";
            try {
                String body = httpRaw("GET", GATEWAY + path, null, token);
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    private static void post(final String path, final JSONObject body, final String token,
                             final JsonCallback cb) {
        new Thread(() -> {
            try {
                String resp = httpRaw("POST", GATEWAY + path,
                        body != null ? body.toString() : null, token);
                cb.onResult(new JSONObject(resp), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    private static void requestApp(final String method, final String path,
                                   final JSONObject body, final String appToken,
                                   final JsonCallback cb) {
        new Thread(() -> {
            try {
                String response = httpRawApp(
                        method,
                        GATEWAY + path,
                        body != null ? body.toString() : null,
                        appToken,
                        "1,1"
                );
                cb.onResult(new JSONObject(response), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    private static String httpRaw(String method, String urlStr, String body, String token)
            throws Exception {
        return httpRawApp(method, urlStr, body, token, "1,5");
    }

    /** 同 httpRaw，但可指定 ApplicationType。 */
    private static String httpRawApp(String method, String urlStr, String body,
                                     String token, String appType) throws Exception {
        return httpRawApp(method, urlStr, body, token, appType, clientVersion);
    }

    /** 同 httpRawApp，但显式指定 VersionCode，用于版本探测。 */
    private static String httpRawApp(String method, String urlStr, String body,
                                     String token, String appType, String version) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Android_ilife798_" + version);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("ApplicationType", appType);
            c.setRequestProperty("Accept-Language", "zh-Hans-CN;q=1");
            // 与官方客户端一致：所有接口都需要携带版本号，否则会被判为旧版
            c.setRequestProperty("VersionCode", version);
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", token);
            }
            if (body != null) {
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int http = c.getResponseCode();
            BufferedReader rd = new BufferedReader(new InputStreamReader(
                    http >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) sb.append(line);
            rd.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
