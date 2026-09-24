package com.water.widget;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** 快捷设置磁贴：启动 / 停止当前选择的饮水设备。 */
public class WaterTileService extends TileService {
    private static final String LABEL_START = "启动设备";
    private static final String LABEL_STOP = "停止设备";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshTileFromStatus();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        refreshTileFromStatus();
    }

    @Override
    public void onClick() {
        super.onClick();
        final String did = WaterApi.getDid(this);
        if (!WaterApi.isConfigured(this) || did.isEmpty()) {
            showToast("请先在 App 中登录并选择设备");
            openConfig(false);
            return;
        }

        Account account = AccountStore.getCurrent(this);
        final String appToken = account == null || account.appToken == null
                ? "" : account.appToken;
        updateTile(Tile.STATE_ACTIVE, "确认状态…");
        // 以服务端返回的设备状态为准，而不是本进程的监测标记，
        // 这样 App 被杀掉之后磁贴仍然能正确显示启动 / 停止。
        WaterApi.statusWithToken(appToken, did, (running, err) -> mainHandler.post(() -> {
            if (running == null) {
                WaterApi.toggleWithToken(appToken, did, status -> mainHandler.post(() -> {
                    showToast(status);
                    refreshTileFromStatus();
                }));
                return;
            }
            if (running) {
                stopDevice(appToken, did);
            } else {
                startDevice(appToken, did);
            }
        }));
    }

    private void startDevice(String appToken, String did) {
        updateTile(Tile.STATE_ACTIVE, "启动中…");
        WaterApi.startWithToken(appToken, did, status -> mainHandler.post(() -> {
            showToast(status);
            mainHandler.postDelayed(this::refreshTileFromStatus, 2000L);
        }));
    }

    private void stopDevice(String appToken, String did) {
        updateTile(Tile.STATE_ACTIVE, "停止中…");
        WaterApi.stopWithToken(appToken, did, status -> mainHandler.post(() -> {
            showToast(status);
            refreshTileFromStatus();
        }));
    }

    /** 查询设备真实状态，决定磁贴显示「启动」还是「停止」。 */
    private void refreshTileFromStatus() {
        if (!WaterApi.isConfigured(this)) {
            updateTile(Tile.STATE_INACTIVE, LABEL_START);
            return;
        }
        final String did = WaterApi.getDid(this);
        Account account = AccountStore.getCurrent(this);
        final String appToken = account == null || account.appToken == null
                ? "" : account.appToken;
        if (did.isEmpty() || appToken.isEmpty()) {
            updateTile(Tile.STATE_INACTIVE, LABEL_START);
            return;
        }
        WaterApi.statusWithToken(appToken, did, (running, err) -> mainHandler.post(() -> {
            if (running != null && running) {
                updateTile(Tile.STATE_ACTIVE, LABEL_STOP);
            } else {
                updateTile(Tile.STATE_INACTIVE, LABEL_START);
            }
        }));
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @SuppressWarnings("deprecation")
    private void openConfig(boolean recovery) {
        Intent cfg = new Intent(this, ConfigActivity.class);
        if (recovery) cfg.putExtra(ConfigActivity.EXTRA_OPEN_WATER_RECOVERY, true);
        cfg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    cfg,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(cfg);
        }
    }

    private void updateTile(int state, String label) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(state);
        tile.setLabel(label);
        tile.updateTile();
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
