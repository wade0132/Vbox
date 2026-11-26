package com.github.vying.osc.subtitle

import com.github.vying.osc.subtitle.model.TimedTextObject

class SubtitleLoadSuccessResult {
    @JvmField
    var fileName: String? = null

    @JvmField
    var content: String? = null

    @JvmField
    var timedTextObject: TimedTextObject? = null

    @JvmField
    var subtitlePath: String? = null
}
