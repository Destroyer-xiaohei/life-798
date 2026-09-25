package com.water.widget;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * 应用入口：确定上报给服务端的客户端版本号。
 *
 * 平台要求客户端版本与官方「慧生活798」App 最新版一致，过低或非官方版本都会返回
 * “请升级最新版app”。因此优先读取本机已安装的官方 App 版本（永远与平台期望一致），
 * 其次使用上次探测并持久化的值，最后回退默认值。
 */
public class WaterWidgetApp extends Application {
    public static final String KEY_CLIENT_VERSION = "client_version";

    // 官方「慧生活798」客户端包名（白标）
    private static final String[] OFFICIAL_PACKAGES = {
            "com.cloudora.android",
    };

    @Override
    public void onCreate() {
        super.onCreate();
        String official = officialAppVersion();
        if (!official.isEmpty()) {
            IlifeApi.setClientVersion(official);
            getSharedPreferences(WaterApi.PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CLIENT_VERSION, official)
                    .apply();
            return;
        }
        String saved = getSharedPreferences(WaterApi.PREFS, MODE_PRIVATE)
                .getString(KEY_CLIENT_VERSION, "");
        if (saved != null && !saved.trim().isEmpty()) {
            IlifeApi.setClientVersion(saved);
        }
    }

    private String officialAppVersion() {
        PackageManager pm = getPackageManager();
        for (String pkg : OFFICIAL_PACKAGES) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                String version = info.versionName;
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            } catch (Exception ignored) {
                // 未安装官方 App
            }
        }
        return "";
    }
}
