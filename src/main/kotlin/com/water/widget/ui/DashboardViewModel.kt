package com.water.widget.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.water.widget.Account
import com.water.widget.AccountStore
import com.water.widget.TaskRunRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val summary: DashboardSummaryUiState = DashboardUiStateFactory.from(null, 0),
    val accounts: List<DashboardAccountUiState> = emptyList(),
    val tasks: TaskUiState = TaskUiStateFactory.from(emptyList(), false, 0, emptyList()),
    val homeRefreshing: Boolean = false,
    val deviceSyncing: Boolean = false
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var currentScore: Int? = null
    private var currentWalletBalance: Double? = null
    private var currentScoreLogs: JSONObject? = null
    private var homeRefreshing = false
    private var deviceSyncing = false

    init {
        reloadAccounts()
        viewModelScope.launch {
            TaskRunRepository.state.collect {
                publish()
            }
        }
    }

    fun reloadAccounts(resetScore: Boolean = false) {
        if (resetScore) {
            currentScore = null
            currentWalletBalance = null
            currentScoreLogs = null
        }
        publish()
    }

    fun setCurrentScoreData(score: Int?, logs: JSONObject?, walletBalance: Double? = null) {
        currentWalletBalance = walletBalance
        currentScore = score
        currentScoreLogs = logs
        publish()
    }

    fun setDeviceSyncing(syncing: Boolean) {
        deviceSyncing = syncing
        publish()
    }

    fun setHomeRefreshing(refreshing: Boolean) {
        homeRefreshing = refreshing
        publish()
    }

    fun selectAccount(phone: String): Account? {
        val storeContext = getApplication<Application>()
        val account = AccountStore.get(storeContext, phone) ?: return null
        AccountStore.setCurrent(storeContext, account.phone)
        currentScore = null
        currentWalletBalance = null
        currentScoreLogs = null
        publish()
        return account
    }

    private fun publish() {
        val storeContext = getApplication<Application>()
        val accounts = AccountStore.list(storeContext)
        val current = AccountStore.getCurrent(storeContext)
        val taskRun = TaskRunRepository.state.value
        val usage = current?.let { account ->
            val accountKey = account.phone?.takeIf(String::isNotBlank)
                ?: account.uid?.takeIf(String::isNotBlank)
                ?: return@let WaterUsageUiState()
            UsageHistoryStore.mergeAndRead(storeContext, accountKey, currentScoreLogs)
        }
        _uiState.update {
            DashboardUiState(
                summary = DashboardUiStateFactory.from(
                    current,
                    accounts.size,
                    currentScore,
                    currentScoreLogs,
                    usageOverride = usage,
                    walletBalance = currentWalletBalance
                ),
                accounts = DashboardUiStateFactory.accountsFrom(accounts, current?.phone),
                tasks = TaskUiStateFactory.from(
                    accounts,
                    taskRun.running,
                    taskRun.totalGained,
                    taskRun.logs
                ),
                homeRefreshing = homeRefreshing,
                deviceSyncing = deviceSyncing
            )
        }
    }

}
