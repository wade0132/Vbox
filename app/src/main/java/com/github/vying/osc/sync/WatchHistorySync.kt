package com.github.vying.osc.sync

import com.github.vying.osc.api.AppBootstrap
import com.github.vying.osc.bean.VodInfo
import com.github.vying.osc.data.AppGraph
import com.github.vying.osc.data.CurrentSubscription
import com.github.vying.osc.data.PlaybackProgress
import com.github.vying.osc.data.WatchProgressIndex
import com.github.vying.osc.data.WatchProgressStore
import com.github.vying.osc.event.UiChange
import com.github.vying.osc.event.UiEvents
import com.github.vying.osc.util.HistoryHelper
import com.github.vying.osc.util.KV
import com.github.vying.osc.util.MD5
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** 观影记录多端同步：把本机历史 + 播放进度与坚果云上的快照做最后写入优先合并。 */
object WatchHistorySync {

    private const val PROGRESS_KEY = "playback_progress"
    private const val MAX_CAS_ATTEMPTS = 5
    private const val MAX_RECORDS = 2000
    private val GSON = Gson()

    data class Result(
        val ok: Boolean,
        val message: String,
        val records: Int = 0,
        val showMessage: Boolean = false,
    )

    /** historyRecords 只统计真正的历史条目；changes 含分集进度写入，不能当作“记录数”展示。 */
    private data class ApplyResult(val historyRecords: Int = 0, val changes: Int = 0)

    private val running = AtomicBoolean(false)

    suspend fun syncNow(): Result = withContext(Dispatchers.IO) {
        val config = NutstoreSyncConfig.load()
        if (!config.enabled) return@withContext Result(false, "同步未开启")
        if (!config.isConfigured()) return@withContext Result(false, "坚果云账号未配置")
        if (HistoryHelper.isIncognito()) return@withContext Result(false, "隐身模式不同步")
        if (!config.normalizedServer().startsWith("https://") && !isLocal(config.normalizedServer())) {
            return@withContext Result(false, "坚果云 WebDAV 必须使用 HTTPS")
        }
        // 并发触发不直接丢弃：排队等上一次结束后再执行，避免自动同步与手动同步互相「撞掉」。
        var waited = 0L
        while (!running.compareAndSet(false, true)) {
            if (waited >= 60_000L) return@withContext Result(false, "上一次同步尚未结束")
            delay(500L)
            waited += 500L
        }
        try {
            val client = NutstoreWebDavClient(config)
            // 先自检认证，401/403 直接给出可操作的提示，避免笼统的「创建目录失败」
            val connectionError = client.testConnection()
            if (connectionError != null) {
                NutstoreSyncConfig.save(config.withResult(0L, connectionError))
                return@withContext Result(false, connectionError)
            }
            client.ensureFolder()
            val cid = CurrentSubscription.cid()
            val url = client.fileUrl("avbox-history-${MD5.string2MD5(cid) ?: "default"}.json")
            val now = System.currentTimeMillis()
            var lastConflict = false
            var merged: SyncSnapshot? = null
            var etag: String? = null
            for (attempt in 0 until MAX_CAS_ATTEMPTS) {
                val remoteFile = client.load(url)
                val remote = SyncSnapshot.fromJson(remoteFile?.text)
                etag = remoteFile?.etag
                val candidate = WatchHistorySnapshot.merge(
                    localSnapshot(cid), remote, now, NutstoreSyncConfig.tombstoneRetentionMs()
                )
                applyLocally(candidate)
                if (client.save(url, etag, SyncSnapshot.toJson(candidate))) {
                    merged = candidate
                    lastConflict = false
                    break
                }
                lastConflict = true
            }
            if (merged == null) {
                val message = if (lastConflict) "同步冲突，请稍后重试" else "同步未完成"
                NutstoreSyncConfig.save(config.withResult(0L, message))
                return@withContext Result(false, message)
            }
            NutstoreSyncConfig.save(config.withResult(System.currentTimeMillis(), ""))
            // 展示口径必须与历史页完全一致：历史页按「当前订阅 cid + 本地库」显示，
            // 而云端快照会混入纯进度条目、其他设备残留、以及 dataJson 无法解析的脏条目，
            // 直接用 merged/applied 统计会出现「本地 3 条却提示 5 条」。
            val displayCid = AppBootstrap.subscriptionCid.value
            val displayCount = AppGraph.historyRepository.getVodRecords(displayCid, MAX_RECORDS).size
            Result(true, "同步完成", displayCount)
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            NutstoreSyncConfig.save(config.withResult(0L, message))
            Result(false, message)
        } finally {
            running.set(false)
        }
    }

    /**
     * 一键清除坚果云同步目录中的所有观影记录快照。
     * 只删云端文件，不动本机历史；若自动同步仍开启，本机记录之后会再次上传。
     */
    suspend fun clearRemoteHistory(): Result = withContext(Dispatchers.IO) {
        val config = NutstoreSyncConfig.load()
        if (!config.isConfigured()) {
            return@withContext Result(false, "坚果云账号未配置", showMessage = true)
        }
        if (!config.normalizedServer().startsWith("https://") && !isLocal(config.normalizedServer())) {
            return@withContext Result(false, "坚果云 WebDAV 必须使用 HTTPS", showMessage = true)
        }
        if (!running.compareAndSet(false, true)) {
            return@withContext Result(false, "上一次同步尚未结束", showMessage = true)
        }
        try {
            val client = NutstoreWebDavClient(config)
            val connectionError = client.testConnection()
            if (connectionError != null) {
                return@withContext Result(false, connectionError, showMessage = true)
            }
            val files = client.listRemoteHistoryFiles()
            for (file in files) client.delete(file)
            NutstoreSyncConfig.save(config.withResult(System.currentTimeMillis(), ""))
            val message = if (files.isEmpty()) {
                "云端没有可清除的观影记录"
            } else {
                "已清除云端 ${files.size} 个观影记录文件"
            }
            Result(true, message, files.size, showMessage = true)
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            NutstoreSyncConfig.save(config.withResult(0L, message))
            Result(false, message, showMessage = true)
        } finally {
            running.set(false)
        }
    }

    /** 收集本机历史与播放进度；不修改任何状态。 */
    private suspend fun localSnapshot(cid: String): SyncSnapshot {
        val snapshot = SyncSnapshot()
        snapshot.cid = cid
        snapshot.deviceId = NutstoreSyncConfig.load().deviceId
        snapshot.updatedAt = System.currentTimeMillis()
        val progress = readProgress()
        val records = AppGraph.historyRepository.getVodRecords(cid, MAX_RECORDS)
        val vodByOwner = HashMap<String, VodInfo>()
        for (info in records) {
            val sourceKey = info.sourceKey.orEmpty()
            val vodId = info.id.orEmpty()
            if (sourceKey.isBlank() || vodId.isBlank()) continue
            val key = SyncSnapshot.key(sourceKey, vodId)
            vodByOwner[key] = info
            val entry = SyncEntry()
            entry.sourceKey = sourceKey
            entry.vodId = vodId
            entry.recordAt = info.updateTime
            entry.dataJson = GSON.toJson(info)
            val raw = progress[key]
            if (!raw.isNullOrBlank()) fillProgress(entry, raw)
            snapshot.entries[key] = entry
        }
        // 有些播放进度尚未产生历史记录，也要带上去，否则换设备后进度会丢。
        for ((key, raw) in progress) {
            if (snapshot.entries.containsKey(key) || raw.isNullOrBlank()) continue
            val split = key.indexOf('|')
            val entry = SyncEntry()
            entry.sourceKey = if (split > 0) key.substring(0, split) else key
            entry.vodId = if (split >= 0 && split < key.length - 1) key.substring(split + 1) else ""
            if (entry.vodId.isBlank()) continue
            fillProgress(entry, raw)
            snapshot.entries[key] = entry
        }
        // 关键：续播位置存在 MD5(progressKey) 的缓存里，必须按分集原样上报，
        // 只报 sourceKey|vodId 的百分比，另一台设备点播放仍会从 0 开始。
        collectEpisodeProgress(snapshot, progress, vodByOwner)
        return snapshot
    }

    private suspend fun collectEpisodeProgress(
        snapshot: SyncSnapshot,
        titleProgress: Map<String, String>,
        vodByOwner: Map<String, VodInfo>,
    ) {
        for (indexKey in KV.keys(WatchProgressIndex.KEY_PREFIX)) {
            val owner = WatchProgressIndex.ownerOf(indexKey)
            if (owner.isBlank()) continue
            val indexed = WatchProgressIndex.decode(KV.get(indexKey, "")) ?: continue
            val info = vodByOwner[owner]
            val raw = titleProgress[owner]
            val titlePercent = WatchHistorySnapshot.parsePercent(raw)
            val titleDuration = rawLong(raw, "d")
            val titleAt = rawLong(raw, "t")
            for (ep in indexed.eps) {
                if (ep.isBlank()) continue
                val stored = AppGraph.cacheRepository.get(MD5.string2MD5(ep)) ?: continue
                val position = when (stored) {
                    is Number -> stored.toLong()
                    is String -> stored.toLongOrNull() ?: 0L
                    else -> 0L
                }
                if (position <= 0L) continue
                val split = owner.indexOf('|')
                val item = SyncProgress()
                item.key = ep
                item.owner = owner
                item.sourceKey = if (split >= 0) owner.substring(0, split) else info?.sourceKey.orEmpty()
                item.vodId = if (split >= 0 && split < owner.length - 1) owner.substring(split + 1) else info?.id.orEmpty()
                item.positionMs = position
                item.durationMs = titleDuration
                item.percent = if (titleDuration > 0L && titlePercent >= 0) {
                    (position * 100 / titleDuration).toInt().coerceIn(0, 100)
                } else {
                    titlePercent
                }
                item.at = maxOf(indexed.at, titleAt)
                item.flag = info?.playFlag.orEmpty()
                item.index = info?.playIndex ?: -1
                item.seriesName = info?.playFlag?.let { flag ->
                    info.seriesMap?.get(flag)?.getOrNull(info.playIndex)?.name
                }.orEmpty()
                snapshot.progress[ep] = item
            }
        }
    }

    /** 把合并结果写回本机：历史条目、分集进度、删除标记。历史记录数与进度变更数分开统计。 */
    private suspend fun applyLocally(snapshot: SyncSnapshot): ApplyResult {
        val local = localSnapshot(CurrentSubscription.cid())
        val progress = readProgress()
        var progressChanged = false
        var changes = 0
        var historyRecords = 0
        val touchedOwners = HashSet<String>()

        // 1. 分集位置必须写进播放器真正读取的进度缓存，否则点开视频仍从 0 开始。
        for ((key, remoteProgress) in snapshot.progress) {
            val current = local.progress[key]
            if (!shouldApplyProgress(remoteProgress, current)) continue
            if (remoteProgress.positionMs <= 0L) continue
            WatchProgressStore.applyRemote(
                remoteProgress.owner, key, remoteProgress.positionMs, remoteProgress.durationMs
            )
            touchedOwners.add(remoteProgress.owner)
            progressChanged = true
            changes++
        }

        // 2. 历史条目与标题级百分比（历史页进度条读取 playback_progress）。
        for ((key, remote) in snapshot.entries) {
            val current = local.entries[key]
            if (remote.deleted) {
                if (current == null) continue
                val info = parseVodInfo(current.dataJson) ?: continue
                AppGraph.historyRepository.deleteVodRecord(current.sourceKey, info)
                WatchProgressStore.clear(current.sourceKey + "|" + current.vodId, key, true)
                for (item in snapshot.progress.values) {
                    if (item.owner == key) WatchProgressStore.clear(item.owner, item.key, true)
                }
                if (progress.remove(key) != null) progressChanged = true
                changes++
                continue
            }
            if (remote.recordAt > (current?.recordAt ?: -1L) && remote.dataJson.isNotBlank()) {
                val info = parseVodInfo(remote.dataJson)
                if (info != null && remote.sourceKey.isNotBlank() && remote.vodId.isNotBlank()) {
                    AppGraph.historyRepository.applyRemoteVodRecord(remote.sourceKey, info, remote.recordAt)
                    historyRecords++
                    changes++
                }
            }
            val hadEpisodeProgress = key in touchedOwners
            val newestProgress = snapshot.progress.values.asSequence()
                .filter { it.owner == key && !it.isEmpty() }
                .maxByOrNull { it.at }
            val percent = if (newestProgress != null && newestProgress.percent >= 0) {
                newestProgress.percent
            } else {
                remote.percent
            }
            val duration = if (newestProgress != null && newestProgress.durationMs > 0L) {
                newestProgress.durationMs
            } else {
                remote.durationMs
            }
            val progressAt = maxOf(remote.progressAt, newestProgress?.at ?: 0L)
            if (percent >= 0 && progressAt > (current?.progressAt ?: -1L)) {
                if (duration > 0L && percent > 0) {
                    // 兼容旧快照：只有标题百分比时，按当前 VodInfo 还原播放器 progressKey。
                    val position = (duration * percent / 100L).coerceAtMost(duration)
                    val info = parseVodInfo(remote.dataJson)
                    WatchProgressStore.applyRemote(key, reconstructedProgressKey(remote, info) ?: key, position, duration)
                    touchedOwners.add(key)
                }
                progress[key] = PlaybackProgress.encodeEntry(percent, duration.toInt(), progressAt)
                progressChanged = true
                if (!hadEpisodeProgress) changes++
            }
        }
        if (progressChanged) {
            KV.put(PROGRESS_KEY, progress)
            // 同步直接写 KV 绕过了 PlaybackProgress.write()，必须主动刷新百分比 StateFlow。
            PlaybackProgress.refreshFromDisk()
        }
        AppBootstrap.refreshSubscriptionCid()
        if (changes > 0) UiEvents.post(UiChange.HistoryChanged)
        return ApplyResult(historyRecords, changes)
    }

    private fun shouldApplyProgress(remote: SyncProgress, local: SyncProgress?): Boolean {
        if (local == null) return true
        if (local.isEmpty()) return !remote.isEmpty()
        if (remote.isEmpty()) return false
        return remote.at > local.at
    }

    private fun reconstructedProgressKey(entry: SyncEntry, info: VodInfo?): String? {
        if (entry.sourceKey.isBlank() || entry.vodId.isBlank()) return null
        val flag = info?.playFlag
        if (flag.isNullOrBlank()) return null
        val index = info?.playIndex ?: 0
        val name = info?.seriesMap?.get(flag)?.getOrNull(index)?.name.orEmpty()
        return entry.sourceKey + entry.vodId + flag + index.toString() + name
    }

    private fun readProgress(): HashMap<String, String> {
        val value = KV.get(PROGRESS_KEY, HashMap<String, String>())
        @Suppress("UNCHECKED_CAST")
        return value as? HashMap<String, String> ?: HashMap()
    }

    private fun fillProgress(entry: SyncEntry, raw: String) {
        entry.percent = WatchHistorySnapshot.parsePercent(raw)
        entry.durationMs = rawLong(raw, "d")
        entry.progressAt = rawLong(raw, "t")
    }

    private fun rawObject(raw: String?): JsonObject? = try {
        if (raw.isNullOrBlank()) null else JsonParser.parseString(raw).asJsonObject
    } catch (t: Throwable) {
        null
    }

    private fun rawLong(raw: String?, field: String): Long {
        val obj = rawObject(raw) ?: return 0L
        return try {
            obj.get(field)?.takeUnless { it.isJsonNull }?.asLong ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }

    private fun parseVodInfo(json: String): VodInfo? = try {
        GSON.fromJson(json, VodInfo::class.java)
    } catch (t: Throwable) {
        null
    }

    private fun isLocal(url: String): Boolean =
        url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")
}
