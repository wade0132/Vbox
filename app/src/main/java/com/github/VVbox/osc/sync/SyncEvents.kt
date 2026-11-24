package com.github.VVbox.osc.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 自动同步的结果通知。
 * 调度器在后台完成一轮同步（含成功、失败、重试中）后发出，
 * 同步界面可见时据此弹出提示并刷新「上次自动同步」状态。
 */
data class SyncNotice(
    val ok: Boolean,
    val message: String,
    val records: Int = 0,
    val at: Long = System.currentTimeMillis(),
    val reason: String = "",
    /** false 表示仍在重试，只更新状态文本，不弹提示打扰用户。 */
    val final: Boolean = true,
)

object SyncEvents {
    private val _notices = MutableSharedFlow<SyncNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<SyncNotice> = _notices.asSharedFlow()

    fun post(notice: SyncNotice) {
        _notices.tryEmit(notice)
    }
}
