package com.water.widget;

import android.app.Application;

/**
 * 应用入口：加载上一次探测到、被服务端接受的客户端版本号。
 * 平台会随官方 App 更新抬高最低版本下限，探测结果由 ConfigActivity 持久化。
 */
public class WaterWidgetApp extends Application {
    public static final String KEY_CLIENT_VERSION = "client_version";

    @Override
    public void onCreate() {
        super.onCreate();
        String saved = getSharedPreferences(WaterApi.PREFS, MODE_PRIVATE)
                .getString(KEY_CLIENT_VERSION, "");
        if (saved != null && !saved.trim().isEmpty()) {
            IlifeApi.setClientVersion(saved);
        }
    }
}
