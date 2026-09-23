package com.water.widget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TAB_ACCOUNTS = 0
private const val TAB_LOGS = 1

/**
 * 「我的 → 自动领取」：把账号同步到自建服务端，由服务端每天定时领积分，并查看日志。
 */
@Composable
fun ClaimServerScreen(
    url: String,
    apiKey: String,
    onUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    tab: Int,
    onTabChange: (Int) -> Unit,
    busy: Boolean,
    statusText: String,
    statusOk: Boolean,
    accounts: List<ClaimAccountUi>,
    logs: List<ClaimLogUi>,
    logAccountId: Int?,
    onLogAccountChange: (Int?) -> Unit,
    autoRefresh: Boolean,
    onToggleAutoRefresh: () -> Unit,
    onSave: () -> Unit,
    onSync: () -> Unit,
    onRunAll: () -> Unit,
    onRunAccount: (Int) -> Unit,
    onDeleteAccount: (Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("自动领取", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "账号同步到自建服务端，由服务端每天定时领完全部积分。",
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }

        item {
            ConfigCard(
                url = url,
                apiKey = apiKey,
                onUrlChange = onUrlChange,
                onApiKeyChange = onApiKeyChange,
                busy = busy,
                onSave = onSave,
                onSync = onSync,
                onRunAll = onRunAll
            )
        }

        if (statusText.isNotBlank()) {
            item { StatusBanner(text = statusText, ok = statusOk) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = tab == TAB_ACCOUNTS,
                    onClick = { onTabChange(TAB_ACCOUNTS) },
                    label = { Text("托管账号（${accounts.size}）") }
                )
                FilterChip(
                    selected = tab == TAB_LOGS,
                    onClick = { onTabChange(TAB_LOGS) },
                    label = { Text("日志（${logs.size}）") }
                )
            }
        }

        if (tab == TAB_ACCOUNTS) {
            if (accounts.isEmpty()) {
                item { EmptyHint("暂无托管账号 · 点「同步账号」把本机账号推上来") }
            } else {
                items(accounts, key = { it.id }) { account ->
                    AccountRow(
                        account = account,
                        busy = busy,
                        onRun = { onRunAccount(account.id) },
                        onDelete = { onDeleteAccount(account.id) }
                    )
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (autoRefresh) "自动刷新：开" else "自动刷新：关",
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onToggleAutoRefresh) {
                        Text(if (autoRefresh) "暂停" else "开启")
                    }
                    TextButton(onClick = onRefresh) { Text("刷新") }
                }
            }
            item {
                FilterChip(
                    selected = logAccountId == null,
                    onClick = { onLogAccountChange(null) },
                    label = { Text("全部账号") }
                )
            }
            if (logs.isEmpty()) {
                item { EmptyHint("暂无日志 · 点「立即领取全部」后稍等几秒再看") }
            } else {
                items(logs, key = { it.id }) { log -> LogRow(log) }
            }
        }
    }
}

@Composable
private fun ConfigCard(
    url: String,
    apiKey: String,
    onUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    busy: Boolean,
    onSave: () -> Unit,
    onSync: () -> Unit,
    onRunAll: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("服务端配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("服务端地址") },
                placeholder = { Text("http://192.168.1.10:8787") }
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("ApiKey") },
                placeholder = { Text("服务端启动时打印的 ApiKey") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSave, enabled = !busy, shape = RoundedCornerShape(14.dp)) {
                    Text("保存并检测")
                }
                FilledTonalButton(onClick = onSync, enabled = !busy, shape = RoundedCornerShape(14.dp)) {
                    Text("同步账号")
                }
            }
            OutlinedButton(
                onClick = onRunAll,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("立即领取全部")
            }
        }
    }
}

@Composable
private fun StatusBanner(text: String, ok: Boolean) {
    val colors = MaterialTheme.colorScheme
    val container = if (ok) colors.primaryContainer else colors.errorContainer
    val content = if (ok) colors.onPrimaryContainer else colors.onErrorContainer
    Surface(shape = RoundedCornerShape(16.dp), color = container, contentColor = content) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AccountRow(
    account: ClaimAccountUi,
    busy: Boolean,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(account.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (account.phone.isNotBlank() && account.phone != account.title) {
                        Text(account.phone, color = colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Text(
                    account.statusLabel,
                    fontSize = 12.sp,
                    color = if (account.hasToken) colors.primary else colors.error
                )
            }
            Text("上次执行：${account.lastRunText}", color = colors.onSurfaceVariant, fontSize = 12.sp)
            HorizontalDivider(color = colors.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRun, enabled = !busy) { Text("立即领取") }
                TextButton(onClick = onDelete, enabled = !busy) { Text("删除") }
            }
        }
    }
}

@Composable
private fun LogRow(log: ClaimLogUi) {
    val colors = MaterialTheme.colorScheme
    val levelColor = when (log.level) {
        "success" -> Color(0xFF16A34A)
        "warn" -> Color(0xFFD97706)
        "error" -> colors.error
        else -> colors.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(top = 5.dp, end = 8.dp)) {
            Surface(shape = CircleShape, color = levelColor, modifier = Modifier.size(7.dp)) {}
        }
        Column(Modifier.weight(1f)) {
            Text(log.headline, color = colors.onSurfaceVariant, fontSize = 11.sp)
            Text(
                log.message,
                fontSize = 13.sp,
                color = if (log.level == "info") colors.onSurface else levelColor,
                fontWeight = if (log.level == "success") FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}