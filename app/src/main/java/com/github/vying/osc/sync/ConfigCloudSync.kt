package com.github.vying.osc.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用配置的云端备份 / 恢复 / 清除。
 *
 * 配置文件固定名为 avbox-config.json，与观影记录文件同目录但互不干扰。
 * 使用 ETag 条件写入避免多设备互相覆盖；恢复时只覆盖快照中存在的键。
 */
object ConfigCloudSync {

    data class Result(
        val ok: Boolean,
        val message: String,
        val count: Int = 0,
    )

    private val running = AtomicBoolean(false)

    /** 备份本机配置到云端。 */
    suspend fun backup(): Result = withContext(Dispatchers.IO) {
        val config = NutstoreSyncConfig.load()
        if (!config.isConfigured()) return@withContext Result(false, "坚果云账号未配置")
        if (!config.enabled) return@withContext Result(false, "同步未开启，请先在上方打开坚果云同步")
        if (!running.compareAndSet(false, true)) return@withContext Result(false, "上一次操作尚未结束")
        try {
            val client = NutstoreWebDavClient(config)
            val error = client.testConnection()
            if (error != null) return@withContext Result(false, error)
            client.ensureFolder()
            val url = client.fileUrl(AppConfigSync.FILE_NAME)
            val snapshot = AppConfigSync.collect(config.deviceId)
            if (snapshot.totalCount() == 0) return@withContext Result(false, "本机暂无可备份的配置")

            var etag: String? = null
            var saved = false
            repeat(5) {
                val remote = client.load(url)
                // 云端已有配置时做一次时间比较，避免较旧本机覆盖较新云端（用户仍可用强制备份路径）。
                etag = remote?.etag
                if (AppConfigSync.toJson(snapshot).let { client.save(url, etag, it) }) {
                    saved = true
                    return@repeat
                }
            }
            if (!saved) return@withContext Result(false, "云端配置冲突，请重试")
            NutstoreSyncConfig.save(config.withResult(System.currentTimeMillis(), ""))
            Result(true, "已备份 ${snapshot.totalCount()} 项配置到云端", snapshot.totalCount())
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            NutstoreSyncConfig.save(config.withResult(0L, message))
            Result(false, message)
        } finally {
            running.set(false)
        }
    }

    /** 从云端拉取配置并恢复到本机。恢复后需要重启应用使设置生效。 */
    suspend fun restore(): Result = withContext(Dispatchers.IO) {
        val config = NutstoreSyncConfig.load()
        if (!config.isConfigured()) return@withContext Result(false, "坚果云账号未配置")
        if (!running.compareAndSet(false, true)) return@withContext Result(false, "上一次操作尚未结束")
        try {
            val client = NutstoreWebDavClient(config)
            val error = client.testConnection()
            if (error != null) return@withContext Result(false, error)
            val url = client.fileUrl(AppConfigSync.FILE_NAME)
            val remote = client.load(url) ?: return@withContext Result(false, "云端没有配置备份")
            val snapshot = AppConfigSync.fromJson(remote.text)
                ?: return@withContext Result(false, "云端配置格式无效")
            if (snapshot.totalCount() == 0) return@withContext Result(false, "云端配置为空")
            val count = AppConfigSync.apply(snapshot)
            Result(true, "已从云端恢复 $count 项配置，重启应用后生效", count)
        } catch (t: Throwable) {
            Result(false, t.message ?: t.javaClass.simpleName)
        } finally {
            running.set(false)
        }
    }

    /** 删除云端配置文件，不影响本机设置。 */
    suspend fun clearRemote(): Result = withContext(Dispatchers.IO) {
        val config = NutstoreSyncConfig.load()
        if (!config.isConfigured()) return@withContext Result(false, "坚果云账号未配置")
        if (!running.compareAndSet(false, true)) return@withContext Result(false, "上一次操作尚未结束")
        try {
            val client = NutstoreWebDavClient(config)
            val error = client.testConnection()
            if (error != null) return@withContext Result(false, error)
            client.delete(AppConfigSync.FILE_NAME)
            Result(true, "已清除云端配置备份（本机设置不受影响）")
        } catch (t: Throwable) {
            Result(false, t.message ?: t.javaClass.simpleName)
        } finally {
            running.set(false)
        }
    }
}
