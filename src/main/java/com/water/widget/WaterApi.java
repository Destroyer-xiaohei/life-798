package com.water.widget;

import android.content.Context;

/**
 * 出水逻辑（向后兼容入口）。
 * 实际请求委托给 IlifeApi，token/did 从 AccountStore 当前账户读取。
 */
public class WaterApi {
    public static final String PREFS = "water_cfg";

    public interface Callback {
        void onResult(String status);
    }

    /** 设备状态回调：running 非空表示成功；err 非空表示失败（TOKEN_EXPIRED 表示登录过期）。 */
    public interface StatusCallback {
        void onResult(Boolean running, String err);
    }

    /** 启动指定饮水设备。水温由设备上的实体按钮决定。 */
    public static void start(Context ctx, final String did, final Callback cb) {
        IlifeApi.devStart(ctx, did, new IlifeApi.TextCallback() {
            @Override
            public void onResult(String text, String err) {
                if (text != null) {
                    cb.onResult("设备 " + text);
                } else if ("TOKEN_EXPIRED".equals(err)) {
                    cb.onResult("启动失败：登录已过期，请重新登录");
                } else {
                    cb.onResult("启动失败：" + err);
                }
            }
        });
    }

    /** 使用调用时冻结的设备控制登录信息启动设备。 */
    public static void startWithToken(
            final String appToken,
            final String did,
            final Callback cb
    ) {
        IlifeApi.devStartWithToken(appToken, did, new IlifeApi.TextCallback() {
            @Override
            public void onResult(String text, String err) {
                if (text != null) {
                    cb.onResult("设备 " + text);
                } else if ("TOKEN_EXPIRED".equals(err)) {
                    cb.onResult("启动失败：登录已过期，请重新登录");
                } else {
                    cb.onResult("启动失败：" + err);
                }
            }
        });
    }

    /** 停止指定饮水设备出水。 */
    public static void stop(Context ctx, final String did, final Callback cb) {
        IlifeApi.devEnd(ctx, did, new IlifeApi.TextCallback() {
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
