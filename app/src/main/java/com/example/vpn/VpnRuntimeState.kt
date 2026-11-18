package com.example.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface ProtectionStatus {
  data object Idle : ProtectionStatus
  data object Connecting : ProtectionStatus
  data object Active : ProtectionStatus
  data class Error(val message: String) : ProtectionStatus
}

/**
 * Process-wide reactive state bridge between LocalAdBlockVpnService and UI/ViewModel.
 */
object VpnRuntimeState {
  private val _status = MutableStateFlow<ProtectionStatus>(ProtectionStatus.Idle)
  val status: StateFlow<ProtectionStatus> = _status.asStateFlow()

  private val _sessionBlocked = MutableStateFlow(0L)
  val sessionBlocked: StateFlow<Long> = _sessionBlocked.asStateFlow()

  fun setStatus(newStatus: ProtectionStatus) {
    _status.value = newStatus
  }

  fun resetSession() {
    _sessionBlocked.value = 0L
  }

  fun incrementSessionBlocked(delta: Long = 1L): Long {
    var updated = 0L
    _sessionBlocked.update { current ->
      updated = current + delta
      updated
    }
    return updated
  }
}
