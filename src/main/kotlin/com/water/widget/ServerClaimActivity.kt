package com.water.widget

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.water.widget.ui.ClaimAccountUi
import com.water.widget.ui.ClaimLogUi
import com.water.widget.ui.ClaimServerScreen
import com.water.widget.ui.ClaimServerUi
import com.water.widget.ui.WaterTheme

/**
 * 「自动领取」：把本机账号同步到自建积分托管服务端，服务端每天定时领取，
 * 这里负责配置、触发和查看服务端日志。
 */
class ServerClaimActivity : ComponentActivity() {
    private companion object {
        const val AUTO_REFRESH_INTERVAL = 15_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var destroyed = false

    private var url by mutableStateOf("")
    private var apiKey by mutableStateOf("")
    private var tab by mutableStateOf(0)
    private var busy by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private var statusOk by mutableStateOf(true)
    private var accounts by mutableStateOf<List<ClaimAccountUi>>(emptyList())
    private var logs by mutableStateOf<List<ClaimLogUi>>(emptyList())
    private var logAccountId by mutableStateOf<Int?>(null)
    private var autoRefresh by mutableStateOf(true)
    private var pendingDelete by mutableStateOf<ClaimAccountUi?>(null)

    private val autoRefreshTask = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (autoRefresh && ClaimServerClient.isConfigured(this@ServerClaimActivity)) {
                if (tab == 0) refreshAccounts() else refreshLogs()
            }
            mainHandler.postDelayed(this, AUTO_REFRESH_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
        url = ClaimServerClient.baseUrl(this)
        apiKey = ClaimServerClient.apiKey(this)
        render()
        if (ClaimServerClient.isConfigured(this)) {
            checkHealth()
            refreshAccounts()
            refreshLogs()
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(autoRefreshTask)
        mainHandler.postDelayed(autoRefreshTask, AUTO_REFRESH_INTERVAL)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(autoRefreshTask)
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 配置
    private fun saveConfig() {
        if (url.isBlank() || apiKey.isBlank()) {
            statusText = "请先填写服务端地址和 ApiKey"
            statusOk = false
            return
        }
        ClaimServerClient.save(this, url, apiKey)
        url = ClaimServerClient.baseUrl(this)
        apiKey = ClaimServerClient.apiKey(this)
        checkHealth()
    }

    private fun checkHealth() {
        busy = true
        ClaimServerClient.health(this) { json, err ->
            mainHandler.post {
                busy = false
                if (destroyed) return@post
                if (json != null && json.optInt("code", -999) == 0) {
                    statusText = ClaimServerUi.healthText(json)
                    statusOk = true
                } else {
                    statusText = "连接失败：" + ClaimServerUi.messageOf(json, err)
                    statusOk = false
                }
            }
        }
    }

    // ------------------------------------------------------------------ 账号
    /** 把本机所有带积分登录态的账号推给服务端。 */
    private fun syncAccounts() {
        val local = AccountStore.list(this).filter { it.hasToken() && !it.uid.isNullOrBlank() }
        if (local.isEmpty()) {
            toast("本机没有可用于领取积分的账号，请先完成积分登录")
            return
        }
        busy = true
        ClaimServerClient.pushAccounts(this, local) { json, err ->
            mainHandler.post {
                busy = false
                if (destroyed) return@post
                val message = ClaimServerUi.messageOf(json, err)
                statusText = "同步结果：$message"
                statusOk = json != null && json.optInt("code", -999) == 0
                refreshAccounts()
            }
        }
    }

    private fun refreshAccounts() {
        ClaimServerClient.accounts(this) { json, _ ->
            mainHandler.post {
                if (destroyed) return@post
                accounts = ClaimServerUi.accountItems(json)
            }
        }
    }

    private fun deleteAccount(accountId: Int) {
        busy = true
        ClaimServerClient.deleteAccount(this, accountId) { _, _ ->
            mainHandler.post {
                busy = false
                if (destroyed) return@post
                refreshAccounts()
            }
        }
    }

    private fun runAccounts(accountId: Int?) {
        busy = true
        ClaimServerClient.triggerRun(this, accountId) { json, err ->
            mainHandler.post {
                busy = false
                if (destroyed) return@post
                val ok = json != null && json.optInt("code", -999) == 0
                statusText = if (ok) {
                    "已下发领取任务，几秒后刷新日志查看结果"
                } else {
                    "下发失败：" + ClaimServerUi.messageOf(json, err)
                }
                statusOk = ok
                if (ok) {
                    tab = 1
                    mainHandler.postDelayed({ refreshLogs() }, 4_000L)
                    mainHandler.postDelayed({ refreshAccounts() }, 6_000L)
                }
            }
        }
    }

    private fun refreshLogs() {
        ClaimServerClient.logs(this, logAccountId, 200) { json, _ ->
            mainHandler.post {
                if (destroyed) return@post
                logs = ClaimServerUi.logItems(json)
            }
        }
    }

    // ------------------------------------------------------------------ 渲染
    private fun render() {
        setContent {
            WaterTheme(mode = ThemeSettings.mode(this)) {
                ClaimServerScreen(
                    url = url,
                    apiKey = apiKey,
                    onUrlChange = { url = it },
                    onApiKeyChange = { apiKey = it },
                    tab = tab,
                    onTabChange = {
                        tab = it
                        if (it == 1) refreshLogs() else refreshAccounts()
                    },
                    busy = busy,
                    statusText = statusText,
                    statusOk = statusOk,
                    accounts = accounts,
                    logs = logs,
                    logAccountId = logAccountId,
                    onLogAccountChange = {
                        logAccountId = it
                        refreshLogs()
                    },
                    autoRefresh = autoRefresh,
                    onToggleAutoRefresh = { autoRefresh = !autoRefresh },
                    onSave = ::saveConfig,
                    onSync = ::syncAccounts,
                    onRunAll = { runAccounts(null) },
                    onRunAccount = { runAccounts(it) },
                    onDeleteAccount = { id -> pendingDelete = accounts.firstOrNull { it.id == id } },
                    onRefresh = {
                        refreshAccounts()
                        refreshLogs()
                    }
                )

                pendingDelete?.let { account ->
                    AlertDialog(
                        onDismissRequest = { pendingDelete = null },
                        title = { Text("删除托管账号") },
                        text = { Text("确定要从服务端删除「${account.title}」吗？") },
                        confirmButton = {
                            Button(onClick = {
                                pendingDelete = null
                                deleteAccount(account.id)
                            }) { Text("删除") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                        }
                    )
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
