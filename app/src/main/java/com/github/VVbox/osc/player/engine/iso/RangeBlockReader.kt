package com.github.VVbox.osc.player.engine.iso

fun interface RangeBlockReader {
    fun read(start: Long, length: Int): ByteArray
}
