package com.jarvis.assistant.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Live device/assistant state dashboard used before, during, and after automation actions. */
data class DeviceSnapshot(
    val currentApp: String? = null,
    val previousApp: String? = null,
    val recentApps: List<String> = emptyList(),
    val runningTasks: List<String> = emptyList(),
    val screenAccess: Boolean = false,
    val screenVisualization: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val time: String? = null,
    val location: String? = null,
    val wifi: Boolean? = null,
    val bluetooth: Boolean? = null,
    val volume: Int? = null,
    val brightness: Int? = null,
    val isKeyGuardOn: Boolean? = null, 
    val activeCall: Boolean = false,
    val musicState: String = "IDLE",
    val browserTabs: Int = 1,
    val currentTask: String? = null,
    val permissionsMissing: List<String> = emptyList()
)

object DeviceStateStore {
    private val _state = MutableStateFlow(DeviceSnapshot())
    val state: StateFlow<DeviceSnapshot> = _state.asStateFlow()

    val cancelled = AtomicBoolean(false)

    fun update(transform: (DeviceSnapshot) -> DeviceSnapshot) {
        _state.value = transform(_state.value)
    }

    fun noteForeground(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        val prev = _state.value.currentApp
        if (prev == packageName) return
        update {
            it.copy(
                previousApp = prev,
                currentApp = packageName,
                recentApps = (listOf(packageName) + it.recentApps).distinct().take(15)
            )
        }
    }

    fun requestCancel() {
        cancelled.set(true)
    }

    fun resetCancel() {
        cancelled.set(false)
    }

    fun isCancelled(): Boolean = cancelled.get()
}
