package dev.jlz.presence.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NativeClientStatus(
    val serviceRunning: Boolean = false,
    val runtimeConnected: Boolean = false,
    val lastHeartbeatAtMs: Long? = null,
    val lastCommandAction: String? = null,
    val lastError: String? = null
)

object NativeClientDiagnostics {
    private val _status = MutableStateFlow(NativeClientStatus())
    val status: StateFlow<NativeClientStatus> = _status.asStateFlow()

    fun update(block: (NativeClientStatus) -> NativeClientStatus) {
        _status.value = block(_status.value)
    }
}
