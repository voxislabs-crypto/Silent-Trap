package app.silenttrap.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RunState {
    IDLE,
    LISTENING,
    SPEECH,
    ERROR,
}

data class LiveStatus(
    val runState: RunState = RunState.IDLE,
    val levelDbfs: Int = -90,
    val analysisGain: Float = 1f,
    val micLabel: String = "",
    val pendingSaves: Int = 0,
    val uploading: Int = 0,
    val message: String? = null,
)

class LiveStatusStore {
    private val _status = MutableStateFlow(LiveStatus())
    val status: StateFlow<LiveStatus> = _status

    fun update(block: (LiveStatus) -> LiveStatus) {
        _status.value = block(_status.value)
    }
}
