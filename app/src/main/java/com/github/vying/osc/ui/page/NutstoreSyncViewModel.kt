package com.github.vying.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.vying.osc.sync.ConfigCloudSync
import com.github.vying.osc.sync.NutstoreSyncConfig
import com.github.vying.osc.sync.NutstoreSyncScheduler
import com.github.vying.osc.sync.SyncEvents
import com.github.vying.osc.sync.WatchHistorySync
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 自动同步状态快照，供界面展示「上次自动同步」。 */
data class AutoSyncStatus(
    val at: Long = 0L,
    val ok: Boolean = false,
    val message: String = "",
    val pending: Boolean = false,
)

class NutstoreSyncViewModel : ViewModel() {

    private val _config = MutableStateFlow(NutstoreSyncConfig.load())
    val config: StateFlow<NutstoreSyncConfig> = _config.asStateFlow()

    private val _result = MutableStateFlow<WatchHistorySync.Result?>(null)
    val result: StateFlow<WatchHistorySync.Result?> = _result.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _configResult = MutableStateFlow<ConfigCloudSync.Result?>(null)
    val configResult: StateFlow<ConfigCloudSync.Result?> = _configResult.asStateFlow()

    private val _configBusy = MutableStateFlow(false)
    val configBusy: StateFlow<Boolean> = _configBusy.asStateFlow()

    /** 一次性提示：同步完成 / 备份完成 / 恢复完成 / 清除成功，以及各类失败原因。 */
    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notice: SharedFlow<String> = _notice.asSharedFlow()

    private val _autoStatus = MutableStateFlow(readAutoStatus())
    val autoStatus: StateFlow<AutoSyncStatus> = _autoStatus.asStateFlow()

    init {
        // 后台自动同步的结果也回传界面：界面可见时弹提示并刷新状态。
        viewModelScope.launch {
            SyncEvents.notices.collect { notice ->
                _config.value = NutstoreSyncConfig.load()
                _autoStatus.value = readAutoStatus()
                if (notice.final) {
                    _notice.tryEmit((if (notice.ok) "✓ " else "✗ ") + notice.message)
                }
            }
        }
    }

    private fun readAutoStatus(): AutoSyncStatus {
        val c = NutstoreSyncConfig.load()
        return AutoSyncStatus(
            at = maxOf(c.lastAttemptAt, c.lastSyncAt),
            ok = c.lastAutoOk,
            message = c.lastAutoMessage.ifBlank { c.lastError },
            pending = c.pendingSync,
        )
    }

    private fun emitResult(ok: Boolean, message: String) {
        _config.value = NutstoreSyncConfig.load()
        _autoStatus.value = readAutoStatus()
        _notice.tryEmit((if (ok) "✓ " else "✗ ") + message)
    }

    fun update(transform: (NutstoreSyncConfig) -> NutstoreSyncConfig) {
        val prev = _config.value
        val next = transform(prev)
        _config.value = next
        NutstoreSyncConfig.save(next)
        // 刚开启同步且账号已填好：立即同步一轮，不用等下次打开 App。
        if (!prev.enabled && next.enabled && next.isConfigured()) {
            NutstoreSyncScheduler.onConfigChanged()
        }
    }

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            val r = WatchHistorySync.syncNow()
            _result.value = r
            _syncing.value = false
            emitResult(
                r.ok,
                if (r.ok) "同步完成，已同步 ${r.records} 条观影记录" else "同步失败：${r.message}",
            )
        }
    }

    fun clearRemoteHistory() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            val r = WatchHistorySync.clearRemoteHistory()
            _result.value = r
            _syncing.value = false
            emitResult(
                r.ok,
                if (r.ok) "清除成功：${r.message}" else "清除失败：${r.message}",
            )
        }
    }

    fun backupConfig() {
        if (_configBusy.value) return
        viewModelScope.launch {
            _configBusy.value = true
            val r = ConfigCloudSync.backup()
            _configResult.value = r
            _configBusy.value = false
            emitResult(r.ok, if (r.ok) "备份完成：${r.message}" else "备份失败：${r.message}")
        }
    }

    fun restoreConfig(onSuccess: () -> Unit) {
        if (_configBusy.value) return
        viewModelScope.launch {
            _configBusy.value = true
            val r = ConfigCloudSync.restore()
            _configResult.value = r
            _configBusy.value = false
            emitResult(r.ok, if (r.ok) "恢复完成：${r.message}" else "恢复失败：${r.message}")
            if (r.ok) onSuccess()
        }
    }

    fun clearRemoteConfig() {
        if (_configBusy.value) return
        viewModelScope.launch {
            _configBusy.value = true
            val r = ConfigCloudSync.clearRemote()
            _configResult.value = r
            _configBusy.value = false
            emitResult(r.ok, if (r.ok) "清除成功：${r.message}" else "清除失败：${r.message}")
        }
    }
}
