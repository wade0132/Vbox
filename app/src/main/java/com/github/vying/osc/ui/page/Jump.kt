package com.github.vying.osc.ui.page

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.github.vying.osc.ui.DetailActivity
import com.github.vying.osc.ui.SearchActivity

fun Context.jumpToDetail(
    id: String?,
    sourceKey: String?,
    title: String?,
    picture: String?,
    collect: Boolean = false,
    autoPlay: Boolean = false,
) {
    val bundle = Bundle()
    bundle.putString("id", id.orEmpty())
    bundle.putString("sourceKey", sourceKey.orEmpty())
    bundle.putString("title", title)
    bundle.putString("picture", picture)
    bundle.putBoolean("collect", collect)
    bundle.putBoolean("autoPlay", autoPlay)
    startActivity(Intent(this, DetailActivity::class.java).putExtras(bundle))
}

/**
 * 「首页-最近观看」专用：跳过详情页浏览，直接进入全屏播放。
 * 详情页加载时本就会按历史记录恢复 playIndex/playFlag 与续播进度（见
 * DetailViewModel.onDetailResult），这里只是让它在就绪后自动触发一次播放动作。
 */
fun Context.jumpToPlayback(id: String?, sourceKey: String?, title: String?, picture: String?) {
    jumpToDetail(id, sourceKey, title, picture, autoPlay = true)
}

fun Context.jumpToSearch(title: String) {
    // 清缓存后卡片数据可能为空，空标题跳搜索页会命中无有效源的搜索路径而崩溃。
    if (title.isBlank()) return
    val bundle = Bundle()
    bundle.putString("title", title)
    startActivity(Intent(this, SearchActivity::class.java).putExtras(bundle))
}
