package com.github.VVbox.osc.sync

import com.github.VVbox.osc.util.KV
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.UUID

/** 坚果云观影记录同步配置。只存普通字符串，不接触 Room 数据库。 */
data class NutstoreSyncConfig(
    val enabled: Boolean = false,
    val server: String = "https://dav.jianguoyun.com/dav/",
    val username: String = "",
    val password: String = "",
    val folder: String = "AVBox",
    val autoSync: Boolean = true,
    val syncOnOpen: Boolean = true,
    val intervalMinutes: Int = 15,
    val deviceId: String = UUID.randomUUID().toString(),
    val lastSyncAt: Long = 0L,
    val lastError: String = "",
    // 自动同步状态：每次尝试都记录、失败待补标记、结果文案，供同步界面展示与排查。
    val lastAttemptAt: Long = 0L,
    val pendingSync: Boolean = false,
    val lastAutoMessage: String = "",
    val lastAutoOk: Boolean = false
) {

    fun isConfigured(): Boolean =
        server.startsWith("http") && username.isNotBlank() && password.isNotBlank()

    fun normalizedServer(): String {
        val trimmed = server.trim().ifEmpty { DEFAULT_SERVER }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    fun normalizedFolder(): String = folder.trim().replace(Regex("^/+|/+$"), "")

    fun withResult(at: Long, error: String): NutstoreSyncConfig =
        copy(lastSyncAt = if (error.isEmpty()) at else lastSyncAt, lastError = error)

    companion object {
        const val DEFAULT_SERVER = "https://dav.jianguoyun.com/dav/"
        private const val KEY = "nutstore_sync_config"
        private const val TOMBSTONE_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
        private val GSON = Gson()

        fun load(): NutstoreSyncConfig {
            val raw = KV.get(KEY, "")
            if (raw.isNullOrBlank()) return NutstoreSyncConfig()
            return try {
                val obj = JsonParser.parseString(raw).asJsonObject
                NutstoreSyncConfig(
                    enabled = obj.has("enabled") && obj.get("enabled").asBoolean,
                    server = text(obj, "server", DEFAULT_SERVER),
                    username = text(obj, "username", ""),
                    password = text(obj, "password", ""),
                    folder = text(obj, "folder", "AVBox"),
                    autoSync = !obj.has("autoSync") || obj.get("autoSync").asBoolean,
                    syncOnOpen = !obj.has("syncOnOpen") || obj.get("syncOnOpen").asBoolean,
                    intervalMinutes = if (obj.has("intervalMinutes")) obj.get("intervalMinutes").asInt else 15,
                    deviceId = text(obj, "deviceId", UUID.randomUUID().toString()),
                    lastSyncAt = if (obj.has("lastSyncAt")) obj.get("lastSyncAt").asLong else 0L,
                    lastError = text(obj, "lastError", ""),
                    lastAttemptAt = if (obj.has("lastAttemptAt")) obj.get("lastAttemptAt").asLong else 0L,
                    pendingSync = flag(obj, "pendingSync"),
                    lastAutoMessage = text(obj, "lastAutoMessage", ""),
                    lastAutoOk = flag(obj, "lastAutoOk")
                )
            } catch (t: Throwable) {
                NutstoreSyncConfig()
            }
        }

        fun save(config: NutstoreSyncConfig) {
            KV.put(KEY, GSON.toJson(config))
        }

        fun tombstoneRetentionMs(): Long = TOMBSTONE_RETENTION_MS

        private fun text(obj: com.google.gson.JsonObject, key: String, fallback: String): String =
            if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asString else fallback

        private fun flag(obj: com.google.gson.JsonObject, key: String): Boolean =
            obj.has(key) && !obj.get(key).isJsonNull && obj.get(key).asBoolean
    }
}
