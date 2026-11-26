package com.github.vying.osc.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface UiChange {
    data object HistoryChanged : UiChange
    data object PlaybackStarted : UiChange

    /** 首页「最近观看」右上角「全部」：切到记录 tab 并选中历史分栏。 */
    data object OpenRecordsHistory : UiChange
}

object UiEvents {
    private val _changes = MutableSharedFlow<UiChange>(extraBufferCapacity = 16)

    val changes: SharedFlow<UiChange> = _changes.asSharedFlow()

    fun post(change: UiChange) {
        _changes.tryEmit(change)
    }
}
