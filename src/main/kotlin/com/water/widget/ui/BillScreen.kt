package com.water.widget.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.water.widget.BillPanel
import com.water.widget.BillRecord
import com.water.widget.BillUiState
import com.water.widget.RechargeProduct
import com.water.widget.RefundProgress
import com.water.widget.WalletAccount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val billStatusOptions = listOf(
    1 to "未付款",
    3 to "已付款",
    2 to "待确认",
    4 to "付款失败",
    9 to "已取消"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillScreen(
    state: BillUiState,
    panel: BillPanel,
    onPanelChange: (BillPanel) -> Unit,
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onRefresh: () -> Unit,
    onSelectStatus: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onRecordClick: (String) -> Unit,
    onSelectWallet: (String) -> Unit,
    onSelectProduct: (RechargeProduct) -> Unit,
    onRecharge: () -> Unit,
    onRefund: () -> Unit
) {
    var showStatusMenu by remember { mutableStateOf(false) }
    var showRefundDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的账单", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (panel == BillPanel.Records) {
                        Box {
                            IconButton(onClick = { showStatusMenu = true }) {
                                Icon(Icons.Rounded.FilterList, contentDescription = "账单类型")
                            }
                            DropdownMenu(
                                expanded = showStatusMenu,
                                onDismissRequest = { showStatusMenu = false }
                            ) {
                                billStatusOptions.forEach { (status, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                fontWeight = if (status == state.billStatus) {
                                                    FontWeight.SemiBold
                                                } else {
                                                    FontWeight.Normal
                                                }
                                            )
                                        },
                                        onClick = {
                                            showStatusMenu = false
                                            onSelectStatus(status)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = onRefresh,
                        enabled = !state.loading && !state.refreshing
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                WalletHeaderCard(
                    state = state,
                    panel = panel,
                    onSelectWallet = onSelectWallet,
                    onToggleRecharge = {
                        onPanelChange(if (panel == BillPanel.Recharge) BillPanel.Records else BillPanel.Recharge)
                    },
                    onToggleRefund = {
                        onPanelChange(if (panel == BillPanel.Refund) BillPanel.Records else BillPanel.Refund)
                    }
                )
            }

            if (state.loading && !state.refreshing) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            state.errorMessage?.let { message ->
                item { MessageCard(message, error = true) }
            }
            state.statusMessage?.let { message ->
                item { MessageCard(message, error = false) }
            }

            if (state.missingAppToken) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("需要设备登录", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                "账单与钱包使用设备登录信息，请先在账户管理中为当前账号完成设备登录。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onOpenAccounts) { Text("前往账户管理") }
                        }
                    }
                }
                return@LazyColumn
            }

            when (panel) {
                BillPanel.Records -> recordsPanel(state, onLoadMore, onRecordClick)
                BillPanel.Recharge -> rechargePanel(state, onSelectProduct, onRecharge)
                BillPanel.Refund -> item {
                    RefundCard(
                        wallet = state.activeWallet,
                        refundProgress = state.refundProgress,
                        submitting = state.refundSubmitting,
                        onRefund = { showRefundDialog = true }
                    )
                }
            }
        }
    }

    if (showRefundDialog) {
        val wallet = state.activeWallet
        AlertDialog(
            onDismissRequest = { if (!state.refundSubmitting) showRefundDialog = false },
            title = { Text("确认退款") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("可退金额 ¥${formatMoney(wallet?.refundable ?: 0.0)}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "余额将退款至支付宝账号，赠送部分清零，提交后需商家审核。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !state.refundSubmitting,
                    onClick = {
                        showRefundDialog = false
                        onRefund()
                    }
                ) { Text(if (state.refundSubmitting) "提交中…" else "确认退款") }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.refundSubmitting,
                    onClick = { showRefundDialog = false }
                ) { Text("取消") }
            }
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recordsPanel(
    state: BillUiState,
    onLoadMore: () -> Unit,
    onRecordClick: (String) -> Unit
) {
    item {
        SectionTitle("账单记录")
    }
    if (state.records.isEmpty() && !state.loading) {
        item {
            Text(
                "暂无账单记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
    }
    items(state.records, key = { it.id.ifEmpty { it.hashCode().toString() } }) { record ->
        BillRecordRow(record, onRecordClick)
    }
    if (state.hasMore) {
        item {
            OutlinedButton(
                onClick = onLoadMore,
                enabled = !state.loadingMore,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.loadingMore) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("加载更多")
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rechargePanel(
    state: BillUiState,
    onSelectProduct: (RechargeProduct) -> Unit,
    onRecharge: () -> Unit
) {
    item {
        SectionTitle("充值金额")
    }
    item {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.productsLoading && state.products.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else if (state.products.isEmpty()) {
                    Text("暂无可充值金额", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.products.chunked(3).forEach { rowProducts ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowProducts.forEach { product ->
                                val selected = product.id == state.selectedProductId
                                if (selected) {
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        enabled = !state.paying && (state.activeWallet?.chargeEnabled ?: true),
                                        onClick = { onSelectProduct(product) }
                                    ) { Text("¥${formatMoney(product.price)}") }
                                } else {
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        enabled = !state.paying && (state.activeWallet?.chargeEnabled ?: true),
                                        onClick = { onSelectProduct(product) }
                                    ) { Text("¥${formatMoney(product.price)}") }
                                }
                            }
                            repeat(3 - rowProducts.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (state.activeWallet?.chargeEnabled == false) {
                    Text(
                        "该钱包暂不支持充值",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.selectedProductId != null &&
                        !state.paying &&
                        (state.activeWallet?.chargeEnabled ?: true),
                    onClick = onRecharge
                ) {
                    Text(if (state.paying) "充值中…" else "立即充值")
                }
            }
        }
    }
}

@Composable
private fun WalletHeaderCard(
    state: BillUiState,
    panel: BillPanel,
    onSelectWallet: (String) -> Unit,
    onToggleRecharge: () -> Unit,
    onToggleRefund: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val wallet = state.activeWallet
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box {
                Surface(
                    onClick = { if (state.wallets.size > 1) menuExpanded = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.AccountBalanceWallet,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                wallet?.name?.ifEmpty { "钱包" } ?: "钱包",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.wallets.size > 1) {
                                Text(
                                    "点击切换钱包",
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (state.wallets.size > 1) {
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = "切换钱包",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    state.wallets.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.name.ifEmpty { "钱包" }) },
                            onClick = {
                                menuExpanded = false
                                onSelectWallet(item.id)
                            }
                        )
                    }
                }
            }
            Column {
                Text(
                    "总余额",
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                Text(
                    "¥${formatMoney(wallet?.total ?: 0.0)}",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onToggleRecharge
                ) { Text(if (panel == BillPanel.Recharge) "收起充值" else "充值") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onToggleRefund
                ) { Text(if (panel == BillPanel.Refund) "收起退款" else "退款") }
            }
        }
    }
}

@Composable
private fun BillRecordRow(record: BillRecord, onRecordClick: (String) -> Unit) {
    val isRefund = record.dir == 2
    Card(
        onClick = { onRecordClick(record.id) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    record.msg.ifEmpty { "账单记录" },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatDateTime(record.time),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        billStatusName(record.status, record.dir),
                        color = billStatusColor(record.status, record.dir),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(
                        paymentTypeName(record.type),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
            Text(
                (if (isRefund) "+¥" else "¥") + formatMoney(record.payment),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = if (isRefund) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun RefundCard(
    wallet: WalletAccount?,
    refundProgress: RefundProgress?,
    submitting: Boolean,
    onRefund: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("可退余额")
            Text(
                "¥${formatMoney(wallet?.refundable ?: 0.0)}",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Text("赠送余额不支持退款", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            HorizontalDivider()
            BalanceBreakdown(wallet)
            refundProgress?.takeIf { it.active }?.let { progress ->
                val statusText = when (progress.fail) {
                    1 -> "退款失败"
                    0 -> "退款成功"
                    else -> "退款审核中"
                }
                val statusColor = when (progress.fail) {
                    1 -> MaterialTheme.colorScheme.error
                    0 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    "$statusText：${progress.count}笔 ¥${formatMoney(progress.total)}元",
                    color = statusColor,
                    fontSize = 12.sp
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = (wallet?.refundEnabled ?: true) && (wallet?.refundable ?: 0.0) > 0.0 && !submitting,
                onClick = onRefund
            ) { Text(if (submitting) "提交中…" else "立即退款") }
        }
    }
}

@Composable
private fun BalanceBreakdown(wallet: WalletAccount?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BalanceText("线上余额", wallet?.olCash, Modifier.weight(1f))
            BalanceText("线下余额", wallet?.ofCash, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BalanceText("线上赠送", wallet?.olGift, Modifier.weight(1f))
            BalanceText("线下赠送", wallet?.ofGift, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BalanceText(label: String, value: Double?, modifier: Modifier) {
    Text(
        "$label：${formatMoney(value ?: 0.0)}元",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = modifier
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun MessageCard(message: String, error: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Text(message, modifier = Modifier.padding(14.dp))
    }
}

internal fun formatMoney(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

internal fun formatDateTime(millis: Long): String {
    if (millis <= 0L) return "--"
    return dateFormat.format(Date(millis))
}

internal fun paymentTypeName(type: Int): String = when (type) {
    11 -> "微信（APP）"
    12 -> "微信（公众号）"
    13 -> "微信（小程序）"
    21 -> "支付宝（APP）"
    22 -> "支付宝（生活号）"
    23 -> "支付宝（小程序）"
    24 -> "支付宝（扫脸）"
    31 -> "翼支付"
    41 -> "云闪付（APP）"
    43 -> "云闪付（小程序）"
    51 -> "招商银行"
    52 -> "农行支付"
    91 -> "商家钱包"
    92 -> "余额"
    93 -> "一卡通"
    else -> "其他"
}

internal fun productTypeName(cata: Int): String = when (cata) {
    -1 -> "无限制"
    1 -> "钱包充值"
    2 -> "家政服务"
    3 -> "E袋洗"
    4 -> "零售商品"
    5 -> "押金"
    6 -> "设备消费"
    7 -> "设备充值"
    9 -> "VIP会员卡"
    10 -> "权益商品"
    11 -> "缴费服务"
    12 -> "积分抽奖"
    else -> "未知类型"
}

internal fun billStatusName(status: Int, dir: Int): String {
    if (dir == 2) return if (status == 3) "已退款" else "退款中"
    return when (status) {
        1 -> "未付款"
        2 -> "待确认"
        3 -> "已付款"
        4 -> "付款失败"
        5 -> "核算中"
        9 -> "已取消"
        else -> "未知状态"
    }
}

@Composable
internal fun billStatusColor(status: Int, dir: Int): Color = when {
    dir == 2 -> if (status == 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    status == 3 -> MaterialTheme.colorScheme.primary
    status == 4 -> MaterialTheme.colorScheme.error
    status == 1 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
