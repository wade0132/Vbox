package com.github.VVbox.osc.net

import com.github.VVbox.osc.util.StringUtils

object SearchSettings {

    fun isExactMatch(title: String?, keyword: String?): Boolean {
        val normalized = normalize(title)
        return normalized.isNotEmpty() && normalized == normalize(keyword)
    }

    internal fun normalize(text: String?): String = StringUtils.normalizeTitle(text)
}
