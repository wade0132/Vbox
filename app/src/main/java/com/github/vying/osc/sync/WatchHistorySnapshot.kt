package com.github.vying.osc.sync

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * 云端观影记录快照。一个视频用 "sourceKey|vodId" 作为唯一键，
 * 历史条目和播放进度分别按各自时间戳取较新者，避免一侧的新进度被另一侧覆盖。
 *
 * 这是纯数据 + 纯函数，不依赖 Android，可直接跑 JVM 单元测试。
 */
class SyncEntry {
    @SerializedName("s") var sourceKey: String = ""
    @SerializedName("i") var vodId: String = ""
    @SerializedName("r") var recordAt: Long = 0L
    @SerializedName("j") var dataJson: String = ""
    @SerializedName("p") var percent: Int = -1
    @SerializedName("d") var durationMs: Long = 0L
    @SerializedName("t") var progressAt: Long = 0L
    @SerializedName("x") var deleted: Boolean = false
    @SerializedName("xa") var deletedAt: Long = 0L

    fun copy(): SyncEntry {
        val entry = SyncEntry()
        entry.sourceKey = sourceKey
        entry.vodId = vodId
        entry.recordAt = recordAt
        entry.dataJson = dataJson
        entry.percent = percent
        entry.durationMs = durationMs
        entry.progressAt = progressAt
        entry.deleted = deleted
        entry.deletedAt = deletedAt
        return entry
    }

    fun contentAt(): Long = maxOf(recordAt, progressAt)
}

/**
 * 分集进度。key 必须用播放器真正使用的 progressKey：
 * sourceKey + vodId + playFlag + playIndex + 集名，才能被 getSavedProgress() 读出来续播。
 */
class SyncProgress {
    @SerializedName("k") var key: String = ""
    @SerializedName("o") var owner: String = ""
    @SerializedName("s") var sourceKey: String = ""
    @SerializedName("i") var vodId: String = ""
    @SerializedName("p") var positionMs: Long = 0L
    @SerializedName("d") var durationMs: Long = 0L
    @SerializedName("q") var percent: Int = -1
    @SerializedName("t") var at: Long = 0L
    @SerializedName("f") var flag: String = ""
    @SerializedName("n") var index: Int = -1
    @SerializedName("m") var seriesName: String = ""

    fun copy(): SyncProgress {
        val item = SyncProgress()
        item.key = key
        item.owner = owner
        item.sourceKey = sourceKey
        item.vodId = vodId
        item.positionMs = positionMs
        item.durationMs = durationMs
        item.percent = percent
        item.at = at
        item.flag = flag
        item.index = index
        item.seriesName = seriesName
        return item
    }

    fun isEmpty(): Boolean = positionMs <= 0L && percent <= 0
}

class SyncSnapshot {
    var schema: Int = 1
    var updatedAt: Long = 0L
    var deviceId: String = ""
    var cid: String = ""
    var entries: MutableMap<String, SyncEntry> = LinkedHashMap()
    /** 分集级进度，按播放器 progressKey 索引；旧快照没有该字段时会解析为空。 */
    var progress: MutableMap<String, SyncProgress> = LinkedHashMap()

    fun copy(): SyncSnapshot {
        val snapshot = SyncSnapshot()
        snapshot.schema = schema
        snapshot.updatedAt = updatedAt
        snapshot.deviceId = deviceId
        snapshot.cid = cid
        for ((key, entry) in entries) snapshot.entries[key] = entry.copy()
        for ((key, item) in progress) snapshot.progress[key] = item.copy()
        return snapshot
    }

    /**
     * 真正的历史观影记录数。entries 里还会混入只有进度的条目和删除墓碑，
     * 不能直接用 entries.size 当“同步了几条记录”展示。
     */
    fun historyRecordCount(): Int = entries.values.count {
        !it.deleted && it.recordAt > 0L && it.dataJson.isNotBlank()
    }

    companion object {
        private val GSON = Gson()

        fun fromJson(text: String?): SyncSnapshot? {
            if (text.isNullOrBlank()) return null
            val snapshot = try {
                GSON.fromJson(text, SyncSnapshot::class.java) ?: return null
            } catch (t: Throwable) {
                return null
            }
            if (snapshot.schema != 1) return null
            val decoded = snapshot.entries ?: LinkedHashMap()
            snapshot.entries = LinkedHashMap()
            if (decoded.isEmpty()) return snapshot
            @Suppress("UNCHECKED_CAST")
            val restored: MutableMap<String, SyncEntry> = LinkedHashMap()
            for ((key, value) in decoded) {
                if (key.isBlank() || value == null) continue
                restored[key] = value
            }
            snapshot.entries = restored
            @Suppress("UNCHECKED_CAST")
            val rawProgress = snapshot.progress ?: LinkedHashMap()
            val restoredProgress: MutableMap<String, SyncProgress> = LinkedHashMap()
            for ((key, value) in rawProgress) {
                if (key.isBlank() || value == null) continue
                if (value.key.isBlank()) value.key = key
                restoredProgress[key] = value
            }
            snapshot.progress = restoredProgress
            return snapshot
        }

        fun toJson(snapshot: SyncSnapshot): String = GSON.toJson(snapshot)

        fun key(sourceKey: String?, vodId: String?): String =
            (sourceKey.orEmpty() + "|" + vodId.orEmpty())
    }
}

object WatchHistorySnapshot {

    /**
     * 合并本地和远端快照。规则：
     *  - 历史元数据按 recordAt 取新；播放进度按 progressAt 取新；
     *  - 删除标记只有严格晚于对端内容时间时才生效，避免“旧删除”压制“新播放”；
     *  - 超过保留期的墓碑直接丢弃。
     */
    @JvmStatic
    fun merge(local: SyncSnapshot, remote: SyncSnapshot?, now: Long, retentionMs: Long): SyncSnapshot {
        val merged = local.copy()
        if (remote != null) {
            for ((key, remoteEntry) in remote.entries) {
                val localEntry = merged.entries[key]
                if (localEntry == null) {
                    if (!remoteEntry.deleted) merged.entries[key] = remoteEntry.copy()
                    continue
                }
                val remoteDeletesNewer = remoteEntry.deleted && remoteEntry.deletedAt >= localEntry.contentAt()
                val localDeletesNewer = localEntry.deleted && localEntry.deletedAt >= remoteEntry.contentAt()
                if (remoteDeletesNewer) {
                    val tombstone = remoteEntry.copy()
                    tombstone.deleted = true
                    merged.entries[key] = tombstone
                    continue
                }
                if (localDeletesNewer) continue
                if (remoteEntry.recordAt > localEntry.recordAt) {
                    localEntry.sourceKey = remoteEntry.sourceKey
                    localEntry.vodId = remoteEntry.vodId
                    localEntry.recordAt = remoteEntry.recordAt
                    localEntry.dataJson = remoteEntry.dataJson
                }
                if (remoteEntry.progressAt > localEntry.progressAt && remoteEntry.percent >= 0) {
                    localEntry.percent = remoteEntry.percent
                    localEntry.durationMs = remoteEntry.durationMs
                    localEntry.progressAt = remoteEntry.progressAt
                }
                localEntry.deleted = false
                localEntry.deletedAt = 0L
            }
            // 分集进度独立按时间合并；空的 0% 记录不能压制对端真实进度。
            for ((key, remoteProgress) in remote.progress) {
                if (key.isBlank()) continue
                val localProgress = merged.progress[key]
                if (localProgress == null) {
                    merged.progress[key] = remoteProgress.copy()
                    continue
                }
                if (remoteProgress.isEmpty()) continue
                if (localProgress.isEmpty() || remoteProgress.at > localProgress.at) {
                    merged.progress[key] = remoteProgress.copy()
                }
            }
        }
        // 让标题级百分比与最新的分集进度保持一致，兼容只读取 entries 的旧客户端。
        val newestByOwner = LinkedHashMap<String, SyncProgress>()
        for (item in merged.progress.values) {
            if (item.isEmpty() || item.owner.isBlank()) continue
            val previous = newestByOwner[item.owner]
            if (previous == null || item.at > previous.at) newestByOwner[item.owner] = item
        }
        for ((owner, item) in newestByOwner) {
            val entry = merged.entries[owner] ?: run {
                if (item.sourceKey.isBlank() || item.vodId.isBlank()) continue
                val created = SyncEntry()
                created.sourceKey = item.sourceKey
                created.vodId = item.vodId
                merged.entries[owner] = created
                created
            }
            if (entry.deleted || item.at <= entry.progressAt) continue
            if (item.percent >= 0) entry.percent = item.percent
            if (item.durationMs > 0L) entry.durationMs = item.durationMs
            entry.progressAt = item.at
        }
        val iterator = merged.entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.deleted && now - entry.deletedAt > retentionMs) iterator.remove()
            else if (entry.recordAt <= 0L && entry.progressAt <= 0L && !entry.deleted) iterator.remove()
        }
        merged.updatedAt = now
        return merged
    }

    /** 从 Gson 解析出的原始 JSON 文本中提取 percent，兼容老版本纯数字格式。 */
    @JvmStatic
    fun parsePercent(raw: String?): Int {
        if (raw.isNullOrBlank()) return -1
        raw.toIntOrNull()?.let { return it }
        return try {
            JsonParser.parseString(raw).asJsonObject.get("p")?.asInt ?: -1
        } catch (t: Throwable) {
            -1
        }
    }
}
