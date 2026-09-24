package com.water.widget.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.water.widget.BillDetailInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillDetailScreen(
    billId: String,
    detail: BillDetailInfo?,
    loaded: Boolean,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账单详情", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
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
            val bill = detail
            when {
                bill == null && !loaded -> item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("正在加载账单信息…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                bill == null -> item {
                    Text(
                        "账单加载失败，请稍后重试",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                else -> {
                    item { BillHeaderCard(bill) }
                    item { BillInfoCard(bill) }
                }
            }
        }
    }
}

@Composable
private fun BillHeaderCard(bill: BillDetailInfo) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    bill.deviceName.ifEmpty { bill.msg.ifEmpty { "账单详情" } },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    billStatusName(bill.status, bill.dir),
                    color = billStatusColor(bill.status, bill.dir),
                    fontWeight = FontWeight.Medium
                )
            }
            if (bill.deviceId.isNotEmpty()) {
                Text(
                    bill.deviceId,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        copyToClipboard(context, bill.deviceId, "已复制设备编号")
                    }
                )
            }
            HorizontalDivider()
            if (bill.discount > 0) {
                DetailRow("原价", "¥${formatMoney(bill.payment + bill.discount)}")
                DetailRow("折扣", "-¥${formatMoney(bill.discount)}")
            }
            val showPromo = bill.promoName.isNotEmpty() || bill.status == 1
            if (showPromo) {
                val promoValue = when {
                    bill.promoName.isNotEmpty() -> bill.promoName
                    bill.couponCount > 0 -> "${bill.couponCount} 张可用"
                    else -> "暂无可用"
                }
                DetailRow("优惠券", promoValue)
            }
            DetailRow(
                "合计",
                (if (bill.dir == 2) "+" else "") + "¥" + formatMoney(bill.payment),
                emphasize = true
            )
        }
    }
}

@Composable
private fun BillInfoCard(bill: BillDetailInfo) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("账单信息", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            DetailRow("账单编号", bill.id, onClick = {
                copyToClipboard(context, bill.id, "已复制账单编号")
            })
            if (bill.enterpriseName.isNotEmpty()) DetailRow("商家名称", bill.enterpriseName)
            if (bill.msg.isNotEmpty()) DetailRow("交易描述", bill.msg)
            DetailRow("产品类型", productTypeName(bill.cata))
            DetailRow("支付方式", paymentTypeName(bill.type))
            DetailRow("创建时间", formatDateTime(bill.ctime))
            if (bill.utime > 0) DetailRow("支付时间", formatDateTime(bill.utime))
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    emphasize: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(
            value.ifEmpty { "--" },
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = if (emphasize) 16.sp else 13.sp,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium
        )
    }
}

private fun copyToClipboard(context: Context, text: String, toast: String) {
    if (text.isBlank()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("bill", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}
