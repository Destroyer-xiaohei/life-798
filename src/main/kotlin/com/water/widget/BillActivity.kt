package com.water.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alipay.sdk.app.PayTask
import com.water.widget.ui.BillDetailScreen
import com.water.widget.ui.BillScreen
import com.water.widget.ui.WaterTheme

/**
 * “我的账单”页面：钱包总览 + 账单记录（状态筛选 / 分页）+ 充值 + 退款 + 账单详情。
 * 逻辑与展示对齐 ilife798 的 BillPage / BillDetailPage。
 */
class BillActivity : ComponentActivity() {
    private companion object {
        const val BILL_PAGE_SIZE = 20
        const val DEFAULT_BILL_STATUS = 3
    }

    private var state by mutableStateOf(BillUiState())
    private var panel by mutableStateOf(BillPanel.Records)
    private var detailBillId by mutableStateOf<String?>(null)
    private var detail by mutableStateOf<BillDetailInfo?>(null)
    private var detailLoaded by mutableStateOf(false)
    private var hasLoaded = false
    private var lastAccountKey = ""
    private var billPage = 0
    private var billLoadToken = ""
    private var billLoadGen = 0
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
        render()
    }

    override fun onResume() {
        super.onResume()
        val account = AccountStore.getCurrent(this)
        val accountKey = "${account?.phone.orEmpty()}\u0000${account?.appToken.orEmpty()}"
        if (!hasLoaded || accountKey != lastAccountKey) {
            refreshAll()
        }
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    private fun appToken(): String = AccountStore.getCurrent(this)?.appToken.orEmpty()

    private fun canContinue(): Boolean = !destroyed && !isFinishing

    private fun refreshAll() {
        hasLoaded = true
        val account = AccountStore.getCurrent(this)
        val token = account?.appToken.orEmpty()
        lastAccountKey = "${account?.phone.orEmpty()}\u0000$token"
        if (token.isBlank()) {
            state = BillUiState(
                accountName = account?.displayLabel().orEmpty(),
                missingAppToken = true
            )
            return
        }
        state = state.copy(
            accountName = account.displayLabel(),
            missingAppToken = false,
            loading = true,
            refreshing = true,
            errorMessage = null,
            statusMessage = null
        )
        loadWallet(token)
        loadBillFirstPage(token, DEFAULT_BILL_STATUS)
    }

    private fun loadWallet(token: String) {
        IlifeApi.walletOwnerWithToken(token) { response, error ->
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                if (response == null) {
                    state = state.copy(
                        loading = false,
                        refreshing = false,
                        errorMessage = "加载钱包失败：${error ?: "网络错误"}"
                    )
                    return@runOnUiThread
                }
                val result = BillResponseParser.parseWalletOwner(response)
                val activeId = result.activeWalletId.ifEmpty { state.activeWalletId }
                state = state.copy(
                    wallets = result.wallets,
                    activeWalletId = activeId,
                    refundProgress = result.refundProgress,
                    loading = false,
                    refreshing = false
                )
                loadProducts(token)
            }
        }
    }

    private fun loadProducts(token: String) {
        val wallet = state.wallets.firstOrNull { it.id == state.activeWalletId } ?: return
        if (wallet.eid.isEmpty()) return
        state = state.copy(productsLoading = true)
        IlifeApi.rechargeProductsWithToken(token, wallet.eid) { response, _ ->
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                if (response == null) {
                    state = state.copy(productsLoading = false)
                    return@runOnUiThread
                }
                val products = try {
                    WalletResponseParser.parseProducts(response)
                } catch (_: IllegalArgumentException) {
                    emptyList()
                }
                state = state.copy(products = products, productsLoading = false)
            }
        }
    }

    private fun loadBillFirstPage(token: String, status: Int) {
        val gen = ++billLoadGen
        IlifeApi.billListWithToken(token, 0, BILL_PAGE_SIZE, status) { response, error ->
            runOnUiThread {
                if (!canContinue() || gen != billLoadGen) return@runOnUiThread
                if (response == null) {
                    state = state.copy(
                        loading = false,
                        refreshing = false,
                        errorMessage = "加载账单失败：${error ?: "网络错误"}"
                    )
                    return@runOnUiThread
                }
                val result = BillResponseParser.parseBillList(response)
                billLoadToken = token
                billPage = 0
                state = state.copy(
                    records = result.records,
                    hasMore = result.total > result.records.size,
                    loadingMore = false,
                    loading = false,
                    refreshing = false,
                    errorMessage = null
                )
            }
        }
    }

    private fun loadMoreBills() {
        val token = appToken()
        if (token.isBlank() || state.loadingMore || !state.hasMore || billLoadToken != token) return
        val gen = billLoadGen
        val status = state.billStatus
        state = state.copy(loadingMore = true)
        val nextPage = billPage + 1
        IlifeApi.billListWithToken(token, nextPage, BILL_PAGE_SIZE, status) { response, _ ->
            runOnUiThread {
                if (!canContinue() || gen != billLoadGen) return@runOnUiThread
                val result = response?.let { BillResponseParser.parseBillList(it) }
                    ?: BillResponseParser.BillListResult()
                val seen = state.records.map { it.id }.toMutableSet()
                val appended = result.records.filter { it.id.isEmpty() || seen.add(it.id) }
                billPage = nextPage
                state = state.copy(
                    records = state.records + appended,
                    hasMore = result.records.size >= BILL_PAGE_SIZE,
                    loadingMore = false
                )
            }
        }
    }

    private fun selectBillStatus(status: Int) {
        if (state.billStatus == status) return
        state = state.copy(billStatus = status, records = emptyList(), hasMore = false, loading = true)
        loadBillFirstPage(appToken(), status)
    }

    private fun selectWallet(id: String) {
        if (state.activeWalletId == id) return
        state = state.copy(activeWalletId = id, products = emptyList(), selectedProductId = null)
        loadProducts(appToken())
    }

    private fun openBillDetail(billId: String) {
        detailBillId = billId
        detail = null
        detailLoaded = false
        IlifeApi.billDetailWithToken(appToken(), billId) { response, _ ->
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                detail = response?.let { BillResponseParser.parseBillDetail(it, billId) }
                detailLoaded = true
            }
        }
    }

    private fun closeBillDetail() {
        detailBillId = null
        detail = null
        detailLoaded = false
    }

    private fun startRecharge() {
        val token = appToken()
        val wallet = state.wallets.firstOrNull { it.id == state.activeWalletId } ?: return
        val product = state.products.firstOrNull { it.id == state.selectedProductId } ?: return
        state = state.copy(paying = true, statusMessage = "正在创建充值订单…", errorMessage = null)
        IlifeApi.createRechargeOrderWithToken(token, wallet.eid, wallet.ownerId, product.id) { response, error ->
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                if (response == null) {
                    state = state.copy(paying = false, errorMessage = "创建充值订单失败：${error ?: "网络错误"}")
                    return@runOnUiThread
                }
                val orderId = try {
                    WalletResponseParser.parseOrderId(response)
                } catch (e: IllegalArgumentException) {
                    state = state.copy(paying = false, errorMessage = e.message ?: "创建充值订单失败")
                    return@runOnUiThread
                }
                requestAlipay(token, orderId)
            }
        }
    }

    private fun requestAlipay(token: String, orderId: String) {
        state = state.copy(statusMessage = "正在打开支付宝…")
        IlifeApi.prepayAlipayWithToken(token, orderId) { response, error ->
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                if (response == null) {
                    state = state.copy(paying = false, errorMessage = "发起支付宝支付失败：${error ?: "网络错误"}")
                    return@runOnUiThread
                }
                val payment = try {
                    WalletResponseParser.parsePaymentString(response)
                } catch (e: IllegalArgumentException) {
                    state = state.copy(paying = false, errorMessage = e.message ?: "发起支付宝支付失败")
                    return@runOnUiThread
                }
                launchAlipay(payment)
            }
        }
    }

    private fun launchAlipay(paymentString: String) {
        Thread {
            if (!canContinue()) return@Thread
            val result = try {
                AlipayResultParser.parse(PayTask(this).payV2(paymentString, true))
            } catch (e: Exception) {
                AlipayResult(AlipayResultKind.FAILED, "支付失败：${e.message ?: "支付宝调用异常"}")
            }
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                when (result.kind) {
                    AlipayResultKind.SUCCESS -> {
                        state = state.copy(paying = false, statusMessage = "充值成功，余额已刷新")
                        loadWallet(appToken())
                        loadBillFirstPage(appToken(), state.billStatus)
                    }
                    AlipayResultKind.PROCESSING,
                    AlipayResultKind.CANCELLED -> {
                        state = state.copy(paying = false, statusMessage = result.message, errorMessage = null)
                    }
                    AlipayResultKind.FAILED -> {
                        state = state.copy(paying = false, errorMessage = result.message)
                    }
                }
            }
        }.start()
    }

    private fun submitRefund() {
        val token = appToken()
        val wallet = state.wallets.firstOrNull { it.id == state.activeWalletId } ?: return
        if (wallet.eid.isEmpty()) {
            state = state.copy(errorMessage = "钱包信息缺失，请重新进入")
            return
        }
        state = state.copy(refundSubmitting = true, errorMessage = null, statusMessage = null)
        IlifeApi.refundWalletWithToken(token, wallet.eid) { response, error ->
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                if (response == null) {
                    state = state.copy(refundSubmitting = false, errorMessage = "退款失败：${error ?: "网络错误"}")
                    return@runOnUiThread
                }
                val refundError = BillResponseParser.refundError(response)
                if (refundError == null) {
                    state = state.copy(refundSubmitting = false, statusMessage = "退款申请已提交")
                    loadWallet(token)
                    loadBillFirstPage(token, state.billStatus)
                } else {
                    state = state.copy(refundSubmitting = false, errorMessage = refundError)
                }
            }
        }
    }

    private fun render() {
        setContent {
            WaterTheme(mode = ThemeSettings.mode(this)) {
                val currentDetailId = detailBillId
                if (currentDetailId != null) {
                    BillDetailScreen(
                        billId = currentDetailId,
                        detail = detail,
                        loaded = detailLoaded,
                        onBack = { closeBillDetail() }
                    )
                } else {
                    BillScreen(
                        state = state,
                        panel = panel,
                        onPanelChange = { panel = it },
                        onBack = ::finish,
                        onOpenAccounts = { startActivity(Intent(this, AccountsActivity::class.java)) },
                        onRefresh = { refreshAll() },
                        onSelectStatus = ::selectBillStatus,
                        onLoadMore = ::loadMoreBills,
                        onRecordClick = ::openBillDetail,
                        onSelectWallet = ::selectWallet,
                        onSelectProduct = { product ->
                            state = state.copy(selectedProductId = product.id, statusMessage = null)
                        },
                        onRecharge = ::startRecharge,
                        onRefund = ::submitRefund
                    )
                }
            }
        }
    }
}

enum class BillPanel { Records, Recharge, Refund }

data class BillUiState(
    val accountName: String = "",
    val missingAppToken: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val wallets: List<WalletAccount> = emptyList(),
    val activeWalletId: String = "",
    val billStatus: Int = 3,
    val records: List<BillRecord> = emptyList(),
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val refundProgress: RefundProgress? = null,
    val products: List<RechargeProduct> = emptyList(),
    val productsLoading: Boolean = false,
    val selectedProductId: String? = null,
    val paying: Boolean = false,
    val refundSubmitting: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val activeWallet: WalletAccount?
        get() = wallets.firstOrNull { it.id == activeWalletId } ?: wallets.firstOrNull()
}

private fun Account.displayLabel(): String = name?.takeIf { it.isNotBlank() }
    ?: phone?.takeIf { it.isNotBlank() }
    ?: "当前账户"
