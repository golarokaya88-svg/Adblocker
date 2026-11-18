package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.blocker.AdBlocklistEngine
import com.example.data.ShieldStatsRepository
import com.example.vpn.LocalAdBlockVpnService
import com.example.vpn.ProtectionStatus
import com.example.vpn.VpnRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShieldUiState(
  val protectionStatus: ProtectionStatus = ProtectionStatus.Idle,
  val totalBlocked: Long = 0L,
  val sessionBlocked: Long = 0L,
  val activeRuleCount: Int = 0,
)

class ShieldViewModel(application: Application) : AndroidViewModel(application) {

  private val statsRepository = ShieldStatsRepository(application.applicationContext)
  private val ruleCountFlow = MutableStateFlow(0)

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val engine = AdBlocklistEngine.fromContext(application.applicationContext)
      ruleCountFlow.value = engine.ruleCount
    }
  }

  val uiState: StateFlow<ShieldUiState> =
    combine(
        VpnRuntimeState.status,
        statsRepository.statsFlow,
        VpnRuntimeState.sessionBlocked,
        ruleCountFlow,
      ) { status, persistedStats, sessionBlocked, ruleCount ->
        ShieldUiState(
          protectionStatus = status,
          totalBlocked = persistedStats.totalBlocked,
          sessionBlocked = sessionBlocked,
          activeRuleCount = ruleCount,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ShieldUiState(),
      )

  fun startVpnService() {
    val context = getApplication<Application>().applicationContext
    val intent =
      Intent(context, LocalAdBlockVpnService::class.java).apply {
        action = LocalAdBlockVpnService.ACTION_START
      }
    ContextCompat.startForegroundService(context, intent)
  }

  fun stopVpnService() {
    val context = getApplication<Application>().applicationContext
    val intent =
      Intent(context, LocalAdBlockVpnService::class.java).apply {
        action = LocalAdBlockVpnService.ACTION_STOP
      }
    context.startService(intent)
  }
}
