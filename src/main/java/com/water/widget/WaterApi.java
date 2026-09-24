package com.water.widget;

import android.content.Context;

import org.json.JSONObject;

/**
 * 设备启停逻辑（对齐 ilife798 的实现）。
 *
 * 启动：先查询设备真实状态，已在出水则直接返回；否则优先用商家钱包（ptype=91）启动，
 * 失败后自动切换支付宝免密（ptype=21）兜底。
 * 停止：直接调用 dev/end。
 */
public class WaterApi {
    public static final String PREFS = "water_cfg";

    /** 启动结果，供界面 / 小部件 / 磁贴区分处理。 */
    public enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        FAILED
    }

    public interface Callback {
        void onResult(String status);
    }

    /** 设备状态回调：running 非空表示成功；err 非空表示失败（TOKEN_EXPIRED 表示登录过期）。 */
    public interface StatusCallback {
        void onResult(Boolean running, String err);
    }

    /** 启动指定饮水设备。水温由设备上的实体按钮决定。 */
    public static void start(Context ctx, final String did, final Callback cb) {
        Account account = AccountStore.getCurrent(ctx);
        String token = account == null ? "" : (account.hasAppToken() ? account.appToken : account.token);
        startWithToken(token, did, cb);
    }

    /** 使用调用时冻结的设备控制登录信息启动设备。 */
    public static void startWithToken(
            final String appToken,
            final String did,
            final Callback cb
    ) {
        if (appToken == null || appToken.isEmpty()) {
            cb.onResult("启动失败：需要设备控制登录信息，请先在账户中添加");
            return;
        }
        // 先确认状态，避免设备已出水时重复下发启动。
        // 状态接口失败时不阻断启动（与 ilife798 一致，保证设备可用），仅登录失效才中止。
        IlifeApi.devStatusWithToken(appToken, did, new IlifeApi.JsonCallback() {
            @Override
            public void onResult(JSONObject json, String err) {
                if (json != null) {
                    int code = json.optInt("code", -999);
                    if (code == -99) {
                        cb.onResult("启动失败：登录已过期，请重新登录");
                        return;
                    }
                    if (code == 0 && IlifeApi.isDispensing(json)) {
                        cb.onResult("设备已在出水");
                        return;
                    }
                }
                startWithPayFallback(appToken, did, cb);
            }
        });
    }

    /** 优先商家钱包（91），失败后自动切换支付宝免密（21）。 */
    private static void startWithPayFallback(
            final String appToken,
            final String did,
            final Callback cb
    ) {
        IlifeApi.devStartWithToken(appToken, did, 91, "", new IlifeApi.TextCallback() {
            @Override
            public void onResult(String text, String err) {
                if (text != null) {
                    cb.onResult("设备 " + text);
                    return;
                }
                if ("TOKEN_EXPIRED".equals(err)) {
                    cb.onResult("启动失败：登录已过期，请重新登录");
                    return;
                }
                IlifeApi.devStartWithToken(appToken, did, 21, "", new IlifeApi.TextCallback() {
                    @Override
                    public void onResult(String text2, String err2) {
                        if (text2 != null) {
                            cb.onResult("设备 " + text2);
                        } else if ("TOKEN_EXPIRED".equals(err2)) {
                            cb.onResult("启动失败：登录已过期，请重新登录");
                        } else {
                            String reason = err2 != null && !err2.isEmpty() ? err2 : err;
                            cb.onResult("启动失败：" + (reason == null ? "未知错误" : reason));
                        }
                    }
                });
            }
        });
    }

    /** 停止指定饮水设备出水。 */
    public static void stop(Context ctx, final String did, final Callback cb) {
        Account account = AccountStore.getCurrent(ctx);
        String token = account == null ? "" : (account.hasAppToken() ? account.appToken : account.token);
        stopWithToken(token, did, cb);
    }

    /** 使用调用时冻结的设备控制登录信息停止设备。 */
    public static void stopWithToken(
            final String appToken,
            final String did,
            final Callback cb
    ) {
        IlifeApi.devEndWithToken(appToken, did, new IlifeApi.TextCallback() {
            @Override
            public void onResult(String text, String err) {
                if (text != null) {
                    cb.onResult("设备 " + text);
                } else if ("TOKEN_EXPIRED".equals(err)) {
                    cb.onResult("停止失败：登录已过期，请重新登录");
                } else {
                    cb.onResult("停止失败：" + err);
                }
            }
        });
    }

    /** 先查状态，再决定启动还是停止（对齐 ilife798 的合并按钮行为）。 */
    public static void toggleWithToken(
            final String appToken,
            final String did,
            final Callback cb
    ) {
        statusWithToken(appToken, did, new StatusCallback() {
            @Override
            public void onResult(Boolean running, String err) {
                if ("TOKEN_EXPIRED".equals(err)) {
                    cb.onResult("登录已过期，请重新登录");
                    return;
                }
                if (running == null) {
                    // 状态未知时按空闲处理，交给 startWithToken 内部再次确认。
                    startWithToken(appToken, did, cb);
                    return;
                }
                if (running) {
                    stopWithToken(appToken, did, cb);
                } else {
                    startWithToken(appToken, did, cb);
                }
            }
        });
    }

    /** 查询指定设备当前是否正在出水，用于把启动/停止合并成一个按钮。 */
    public static void status(Context ctx, final String did, final StatusCallback cb) {
        IlifeApi.devStatus(ctx, did, new IlifeApi.JsonCallback() {
            @Override
            public void onResult(org.json.JSONObject json, String err) {
                deliverStatus(json, err, cb);
            }
        });
    }

    /** 使用调用时冻结的设备控制登录信息查询设备状态。 */
    public static void statusWithToken(
            final String appToken,
            final String did,
            final StatusCallback cb
    ) {
        IlifeApi.devStatusWithToken(appToken, did, new IlifeApi.JsonCallback() {
            @Override
            public void onResult(org.json.JSONObject json, String err) {
                deliverStatus(json, err, cb);
            }
        });
    }

    private static void deliverStatus(org.json.JSONObject json, String err,
                                      StatusCallback cb) {
        if (json == null) {
            cb.onResult(null, err);
            return;
        }
        int code = json.optInt("code", -999);
        if (code == 0) {
            cb.onResult(IlifeApi.isDispensing(json), null);
        } else if (code == -99) {
            cb.onResult(null, "TOKEN_EXPIRED");
        } else {
            String msg = json.optString("msg", "");
            cb.onResult(null, msg.isEmpty() ? "code=" + code : msg);
        }
    }

    static String getToken(Context ctx) {
        Account a = AccountStore.getCurrent(ctx);
        return a != null ? a.token : "";
    }

    static String getDid(Context ctx) {
        Account a = AccountStore.getCurrent(ctx);
        return a != null ? a.selectedDeviceId() : "";
    }

    public static boolean isConfigured(Context ctx) {
        Account a = AccountStore.getCurrent(ctx);
        return a != null && a.hasAppToken() && a.hasDevices();
    }
}
