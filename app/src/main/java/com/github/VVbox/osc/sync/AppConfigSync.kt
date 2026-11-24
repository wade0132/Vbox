package com.github.VVbox.osc.sync

import com.github.VVbox.osc.util.KV
import com.github.VVbox.osc.util.KVKeys
import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * 应用配置云备份：把本机设置按分组导出为 JSON，上传坚果云；恢复时原样写回 MMKV。
 *
 * 备份采用「原始编码字符串」而非反序列化后的对象：KV 内部对非字符串值存的是 JSON_PREFIX + json，
 * 原样写回即可无损还原任意类型，不需要知道每个键的具体类型，也不会因版本差异丢字段。
 */
object AppConfigSync {

    const val FILE_NAME = "avbox-config.json"

    /** 配置分组标识与展示用名称（英文标识，供 UI 侧映射文案）。 */
    const val GROUP_APP = "app"
    const val GROUP_VOD = "vod"
    const val GROUP_LIVE = "live"
    const val GROUP_TMDB = "tmdb"

    val GROUPS = listOf(GROUP_APP, GROUP_VOD, GROUP_LIVE, GROUP_TMDB)

    /** 每组包含的 KV 键。key 不存在时会被跳过，不影响备份。 */
    private val GROUP_KEYS: Map<String, List<String>> = mapOf(
        GROUP_VOD to listOf(
            KVKeys.API_URL, KVKeys.EPG_URL, KVKeys.API_HISTORY,
            KVKeys.API_LINE_LIST, KVKeys.API_LINE_SOURCE, KVKeys.SUBSCRIBE_LIST,
            KVKeys.HOME_API, KVKeys.DEFAULT_PARSE, KVKeys.SOURCES_FOR_SEARCH,
            KVKeys.HOME_HOT, KVKeys.HOME_HOT_DAY, KVKeys.DOH_URL, KVKeys.DOH_JSON,
            KVKeys.SEARCH_HISTORY, KVKeys.SEARCH_THREADS, KVKeys.SOURCE_NAME_CACHE,
            KVKeys.BOOT_LOADING_JAR, KVKeys.BOOT_LOADING_SCOPE, KVKeys.BOOT_VOD_SOURCE,
            KVKeys.BOOT_DISABLED_SOURCES, KVKeys.BOOT_DISABLED_JARS, KVKeys.BOOT_SAFE_DISABLED,
            KVKeys.AUTO_SWITCH_LINE,
        ),
        GROUP_LIVE to listOf(
            KVKeys.LIVE_API_URL, KVKeys.LIVE_API_HISTORY, KVKeys.LIVE_API_LINE_LIST,
            KVKeys.LIVE_API_LINE_SOURCE, KVKeys.LIVE_SUBSCRIBE_LIST, KVKeys.LIVE_CHANNEL,
            KVKeys.LIVE_CHANNEL_REVERSE, KVKeys.LIVE_CROSS_GROUP, KVKeys.LIVE_CONNECT_TIMEOUT,
            KVKeys.LIVE_SHOW_NET_SPEED, KVKeys.LIVE_SHOW_TIME, KVKeys.LIVE_GROUP_INDEX,
            KVKeys.LIVE_GROUP_LIST, KVKeys.LIVE_WEB_HEADER, KVKeys.DEFAULT_LOAD_LIVE,
            KVKeys.BOOT_LIVE_SOURCE,
        ),
        GROUP_TMDB to listOf(
            KVKeys.TMDB_ENABLE, KVKeys.TMDB_POSTER_STYLE, KVKeys.TMDB_API_KEY,
            KVKeys.TMDB_API_BASE, KVKeys.TMDB_IMAGE_BASE,
        ),
        GROUP_APP to listOf(
            KVKeys.EXO_DECODE, KVKeys.PLAY_TYPE, KVKeys.PLAY_RENDER, KVKeys.PLAY_SCALE,
            KVKeys.PLAY_TUNNEL, KVKeys.PLAY_PREFER_AAC, KVKeys.KERNEL_PREWARM,
            KVKeys.EXO_VIDEO_DYNAMIC_SCHEDULING, KVKeys.LIVE_PLAY_SCALE,
            KVKeys.SUBTITLE_TEXT_STYLE, KVKeys.SUBTITLE_TEXT_SIZE, KVKeys.SUBTITLE_TIME_DELAY,
            KVKeys.SUBTITLE_EXO_SCALE, KVKeys.SUBTITLE_EXO_POSITION,
            KVKeys.REMOTE_TVBOX, KVKeys.HISTORY_NUM, KVKeys.INCOGNITO,
            KVKeys.HIDE_STATUS_BAR, KVKeys.GESTURE_CONTROL_DISABLED,
            KVKeys.NAV_ANIMATION_DISABLED, KVKeys.NAV_LIVE_HIDDEN,
            KVKeys.MUSIC_AUTO_OPEN_PAGE, KVKeys.LONG_PRESS_SPEED, KVKeys.BUFFER_TIMES,
            KVKeys.PRELOAD_NEXT_EPISODE, KVKeys.PRELOAD_DURATION, KVKeys.PLAY_CACHE,
            KVKeys.EXO_CACHE_SIZE_MB, KVKeys.ISO_LPCM, KVKeys.ISO_TRUEHD_CORE, KVKeys.ISO_PGS,
            KVKeys.DANMU_OPEN, KVKeys.DANMU_MAX_LINE, KVKeys.DANMU_SPEED, KVKeys.DANMU_ALPHA,
            KVKeys.DANMU_SIZE_SCALE, KVKeys.DANMU_RANDOM_COLOR, KVKeys.DANMU_API,
            KVKeys.DANMU_API_USE_DEFAULT, KVKeys.THEME_SOURCE, KVKeys.THEME_MODE,
            KVKeys.THEME_SEED, KVKeys.THEME_PALETTE_STYLE, KVKeys.THEME_PURE_BLACK,
            KVKeys.LIQUID_GLASS_NAVBAR, KVKeys.LIQUID_GLASS_CONTROLS,
            KVKeys.LIQUID_GLASS_BLUR, KVKeys.LIQUID_GLASS_DISTORTION,
            KVKeys.LIQUID_GLASS_TRANSLUCENCY, KVKeys.LIQUID_GLASS_DISPERSION,
            KVKeys.COLLECT_COLUMNS, KVKeys.PICTURE_PRESET, KVKeys.PICTURE_SATURATION,
            KVKeys.PICTURE_CONTRAST, KVKeys.PICTURE_BRIGHTNESS, KVKeys.PICTURE_GAMMA,
            KVKeys.PICTURE_HUE, KVKeys.PICTURE_TEMPERATURE, KVKeys.PICTURE_SHARPNESS,
            KVKeys.PICTURE_SHADOW_LIFT, KVKeys.ANIME4K_ENABLED, KVKeys.ANIME4K_TIER,
            KVKeys.ANIME4K_SHARPEN, KVKeys.ANIME4K_DEBLUR, KVKeys.LOCAL_SOURCE_TREES,
        ),
    )

    /** 永不写入云端的键：同步账号密码、观影进度、纯运行时状态、设备标识。 */
    private val EXCLUDED = setOf(
        "nutstore_sync_config", "playback_progress", "player_is_live",
        "thunder_imei", "thunder_mac", "boot_load_start_elapsed",
        "boot_last_attempt_at", "boot_loading_count",
    )

    data class Snapshot(
        var schema: Int = 1,
        var updatedAt: Long = 0L,
        var deviceId: String = "",
        var groups: MutableMap<String, MutableMap<String, String>> = LinkedHashMap(),
    ) {
        fun totalCount(): Int = groups.values.sumOf { it.size }
    }

    private val GSON = Gson()

    /** 从本地 MMKV 收集各分组的原始编码值。 */
    fun collect(deviceId: String): Snapshot {
        val snapshot = Snapshot(updatedAt = System.currentTimeMillis(), deviceId = deviceId)
        for (group in GROUPS) {
            val values = LinkedHashMap<String, String>()
            for (key in GROUP_KEYS[group].orEmpty()) {
                if (key in EXCLUDED) continue
                val raw = KV.raw(key) ?: continue
                values[key] = raw
            }
            if (values.isNotEmpty()) snapshot.groups[group] = values
        }
        return snapshot
    }

    /**
     * 将快照写回本地。仅覆盖 snapshot 中出现的键，本地其它配置保持不变。
     * 返回实际写入的键数量。
     */
    fun apply(snapshot: Snapshot, selectedGroups: Collection<String> = GROUPS): Int {
        var count = 0
        for (group in selectedGroups) {
            val values = snapshot.groups[group] ?: continue
            val safe = LinkedHashMap<String, String>()
            for ((key, raw) in values) {
                if (key in EXCLUDED || key.isEmpty()) continue
                safe[key] = raw
                count++
            }
            KV.restoreRaw(safe)
        }
        return count
    }

    fun toJson(snapshot: Snapshot): String = GSON.toJson(snapshot)

    fun fromJson(json: String): Snapshot? = try {
        val obj = JsonParser.parseString(json).asJsonObject
        val snapshot = Snapshot()
        if (obj.has("schema") && !obj.get("schema").isJsonNull) snapshot.schema = obj.get("schema").asInt
        if (obj.has("updatedAt") && !obj.get("updatedAt").isJsonNull) snapshot.updatedAt = obj.get("updatedAt").asLong
        if (obj.has("deviceId") && !obj.get("deviceId").isJsonNull) snapshot.deviceId = obj.get("deviceId").asString
        val groupsObj = if (obj.has("groups") && obj.get("groups").isJsonObject) obj.getAsJsonObject("groups") else null
        groupsObj?.entrySet()?.forEach { (group, value) ->
            if (group !in GROUPS || !value.isJsonObject) return@forEach
            val values = LinkedHashMap<String, String>()
            value.asJsonObject.entrySet().forEach { (key, raw) ->
                if (key !in EXCLUDED && raw.isJsonPrimitive) values[key] = raw.asString
            }
            if (values.isNotEmpty()) snapshot.groups[group] = values
        }
        snapshot
    } catch (t: Throwable) {
        null
    }
}
