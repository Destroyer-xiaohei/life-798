package com.water.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RemoteViews;

/**
 * 桌面小部件 Provider。
 * 不使用 android:configure（HyperOS 不兼容），直接添加。
 * 未配置时整个小部件点击打开配置页；已配置时只有一个按钮：
 * 先查设备真实状态，出水时显示「停止」，空闲时显示「启动」。
 */
public class WaterWidgetProvider extends AppWidgetProvider {
    /** 当前版本的唯一按钮动作：按设备状态自动切换启动 / 停止。 */
    public static final String ACTION_TOGGLE = "com.water.widget.ACTION_TOGGLE";
    /** 旧版本小部件可能残留的 action，收到后统一按切换处理，避免点了没反应。 */
    public static final String ACTION_START = "com.water.widget.ACTION_START";
    public static final String ACTION_STOP = "com.water.widget.ACTION_STOP";
    private static final String LEGACY_ACTION_HOT = "com.water.widget.ACTION_HOT";
    private static final String LEGACY_ACTION_COLD = "com.water.widget.ACTION_COLD";
    public static final String EXTRA_DID = "extra_did";

    /** 上次查到的设备出水状态缓存（按 did 存），用于把按钮文案切对。 */
    private static final String KEY_RUNNING_PREFIX = "widget_running_";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, id, null));
        }
        // 先按缓存渲染，再后台查一次真实状态刷新按钮。
        refreshRunningState(context, appWidgetIds);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action)
                || ACTION_START.equals(action)
                || ACTION_STOP.equals(action)
                || LEGACY_ACTION_HOT.equals(action)
                || LEGACY_ACTION_COLD.equals(action)) {
            handleToggle(context, intent);
        }
    }

    /** 按钮点击：先确认设备状态，再决定启动还是停止。 */
    private void handleToggle(Context context, Intent intent) {
        final String did = intent.getStringExtra(EXTRA_DID);
        final int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        Account account = AccountStore.getCurrent(context);
        final String appToken = account == null || account.appToken == null
                ? "" : account.appToken;
        if (did == null || did.isEmpty() || appToken.isEmpty()) {
            render(context, widgetId, "未配置 · 点击设置");
            return;
        }

        render(context, widgetId, "正在确认设备状态…");
        WaterApi.statusWithToken(appToken, did, (running, err) -> mainHandler.post(() -> {
            if ("TOKEN_EXPIRED".equals(err)) {
                render(context, widgetId, "登录已过期，请在 App 重新登录");
                return;
            }
            Boolean effective = running;
            if (effective == null) {
                // 状态接口异常：退回本机监测状态，保证按钮仍可用。
                effective = WaterService.isMonitoring();
            }
            cacheRunning(context, did, effective);
            actOnDevice(context, widgetId, did, appToken, effective);
        }));
    }

    /** running=true 时停止设备，否则启动设备。 */
    private void actOnDevice(Context context, int widgetId, String did, String appToken,
                             boolean running) {
        if (running) {
            WaterApi.stopWithToken(appToken, did, status -> mainHandler.post(() -> {
                cacheRunning(context, did, false);
                if (status != null && status.contains("已停止")) {
                    // 主动停止后结束接水监测，撤掉"正在接水"的进行中通知。
                    WaterService.stopMonitoring(did, "已停止出水", "设备已停止，接水监测结束");
                }
                render(context, widgetId, status);
                refreshRunningState(context, new int[]{widgetId});
            }));
            return;
        }

        WaterService.StartResult result = WaterService.start(context, did, widgetId);
        String status;
        if (result == WaterService.StartResult.STARTED) {
            status = "设备启动中…";
            cacheRunning(context, did, true);
        } else if (result == WaterService.StartResult.ALREADY_RUNNING) {
            status = "已有接水会话正在监测";
            cacheRunning(context, did, true);
        } else {
            status = "启动失败，请稍后重试";
        }
        render(context, widgetId, status);
        refreshRunningState(context, new int[]{widgetId});
    }

    /** 后台查询当前设备是否出水，刷新小部件上的按钮文案。 */
    private void refreshRunningState(Context context, int[] widgetIds) {
        if (widgetIds == null || widgetIds.length == 0) return;
        if (!WaterApi.isConfigured(context)) return;
        Account account = AccountStore.getCurrent(context);
        final String appToken = account == null || account.appToken == null
                ? "" : account.appToken;
        final String did = WaterApi.getDid(context);
        if (appToken.isEmpty() || did.isEmpty()) return;

        WaterApi.statusWithToken(appToken, did, (running, err) -> mainHandler.post(() -> {
            if (running == null) return;
            cacheRunning(context, did, running);
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            for (int id : widgetIds) {
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) continue;
                manager.updateAppWidget(id, buildViews(context, id, null));
            }
        }));
    }

    private void render(Context context, int widgetId, String status) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        AppWidgetManager.getInstance(context).updateAppWidget(
                widgetId, buildViews(context, widgetId, status));
    }

    static RemoteViews buildViews(Context context, int widgetId, String status) {
        boolean configured = WaterApi.isConfigured(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_water);

        if (!configured) {
            // 未配置：整个小部件点击打开主页，隐藏按钮避免出现没有意义的操作。
            Intent cfg = new Intent(context, ConfigActivity.class);
            PendingIntent pi = PendingIntent.getActivity(context, 100 + widgetId, cfg,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(android.R.id.background, pi);
            views.setTextViewText(R.id.widget_status, "未配置 · 点击设置");
            views.setViewVisibility(R.id.btn_start, View.GONE);
            views.setViewVisibility(R.id.btn_stop, View.GONE);
            return views;
        }

        String did = WaterApi.getDid(context);
        Boolean running = cachedRunning(context, did);
        boolean isRunning = running != null && running;

        PendingIntent toggle = buildPI(context, widgetId, did);
        views.setOnClickPendingIntent(R.id.btn_start, toggle);
        views.setOnClickPendingIntent(R.id.btn_stop, toggle);
        // 两个按钮共用一块位置，只显示当前状态对应的那一个，避免布局变形。
        views.setViewVisibility(R.id.btn_start, isRunning ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.btn_stop, isRunning ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.widget_status,
                status != null ? status : defaultStatus(running));
        return views;
    }

    private static String defaultStatus(Boolean running) {
        if (running == null) return "点击检测设备状态并启动 / 停止";
        return running ? "设备正在出水 · 点击停止" : "设备空闲 · 点击启动";
    }

    private static PendingIntent buildPI(Context context, int widgetId, String did) {
        Intent intent = new Intent(context, WaterWidgetProvider.class);
        intent.setAction(ACTION_TOGGLE);
        intent.putExtra(EXTRA_DID, did);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(context, widgetId * 10 + 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(WaterApi.PREFS, Context.MODE_PRIVATE);
    }

    static void cacheRunning(Context context, String did, boolean running) {
        if (did == null || did.isEmpty()) return;
        prefs(context).edit().putBoolean(KEY_RUNNING_PREFIX + did, running).apply();
    }

    static Boolean cachedRunning(Context context, String did) {
        if (did == null || did.isEmpty()) return null;
        SharedPreferences p = prefs(context);
        String key = KEY_RUNNING_PREFIX + did;
        return p.contains(key) ? p.getBoolean(key, false) : null;
    }
}
