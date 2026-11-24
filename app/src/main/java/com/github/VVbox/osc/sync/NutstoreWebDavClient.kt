package com.github.VVbox.osc.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 坚果云 WebDAV 最小客户端。
 *
 * 针对坚果云的兼容点：
 *  - ETag 是不带引号的 opaque 值，回传 If-Match 时必须原样使用；
 *  - GET 不能带 If-None-Match / If-Modified-Since，否则坚果云返回 403；
 *  - 不支持 LOCK，并发控制完全依赖 If-Match / If-None-Match；
 *  - 不用 HEAD 判断文件是否存在（Content-Length 可能为 0），用 GET + 404。
 *
 * 所有方法都是阻塞式，调用方必须在 IO 线程执行。
 */
class NutstoreWebDavClient(private val config: NutstoreSyncConfig) {

    data class RemoteFile(val text: String, val etag: String?)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()


    private val authHeader: String by lazy {
        val token = ByteString.of(*"${config.username}:${config.password}".toByteArray(Charsets.UTF_8)).base64()
        "Basic $token"
    }

    private val rootUrl: String
        get() {
            val base = config.normalizedServer()
            val folder = config.normalizedFolder()
            return if (folder.isEmpty()) base else "$base${encodeSegment(folder)}/"
        }

    fun fileUrl(fileName: String): String = "$rootUrl${encodeSegment(fileName)}"

    /** 目录不存在则创建；已存在视为成功。 */
    fun ensureFolder() {
        if (config.normalizedFolder().isEmpty()) return
        val response = client.newCall(Request.Builder().url(rootUrl).header("Authorization", authHeader).method("MKCOL", null).build()).execute()
        response.use {
            val code = it.code
            if (code == 401) {
                throw IllegalStateException("坚果云认证失败(401)：请确认账号填的是注册邮箱、密码填的是「第三方应用密码」而非登录密码，且密码无多余空格")
            }
            if (code == 403) {
                throw IllegalStateException("坚果云无权限(403)：请检查账号是否开通 WebDAV、应用密码是否有效")
            }
            if (code != 201 && code != 204 && code != 405 && code != 409 && code != 207) {
                throw IllegalStateException("坚果云创建同步目录失败 HTTP $code")
            }
        }
    }

    /**
     * 连接自检：对 WebDAV 根做一次 PROPFIND，明确区分认证失败与路径/网络问题。
     * 返回 null 表示连接正常，否则返回可直接展示给用户的错误说明。
     */
    fun testConnection(): String? {
        val request = Request.Builder()
            .url(config.normalizedServer())
            .header("Authorization", authHeader)
            .header("Depth", "0")
            .method("PROPFIND", "".toRequestBody(null))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200, 207 -> null
                    401 -> "认证失败(401)：密码必须是坚果云「第三方应用密码」，不是登录密码；请到 账户信息→安全选项→第三方应用管理 重新生成"
                    403 -> "无权限(403)：请确认账号已开通 WebDAV，或应用密码有效"
                    404 -> "路径不存在(404)：WebDAV 地址应为 https://dav.jianguoyun.com/dav/"
                    429, 503 -> "坚果云限流或暂不可用，请稍后再试"
                    else -> "连接失败 HTTP ${response.code}"
                }
            }
        } catch (t: Throwable) {
            "网络异常：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** 返回 null 表示远端文件不存在。 */
    fun load(url: String): RemoteFile? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IllegalStateException("坚果云读取同步数据失败 HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            return RemoteFile(body, response.header("ETag"))
        }
    }

    /**
     * 条件写入。etag 为 null 时只允许创建文件；返回 false 表示冲突（412），调用方应重读后重试。
     */
    fun save(url: String, etag: String?, body: String): Boolean {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json; charset=utf-8")
        if (etag.isNullOrEmpty()) builder.header("If-None-Match", "*")
        else builder.header("If-Match", etag)
        val request = builder.put(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 412) return false
            if (!response.isSuccessful) {
                val hint = if (response.code == 403) "（检查应用密码、目录权限或请求频率）" else ""
                throw IllegalStateException("坚果云写入同步数据失败 HTTP ${response.code}$hint")
            }
            return true
        }
    }

    /** 列出同步目录中所有观影记录快照文件名（不修改任何状态）。 */
    fun listRemoteHistoryFiles(): List<String> {
        val request = Request.Builder()
            .url(rootUrl)
            .header("Authorization", authHeader)
            .header("Depth", "1")
            .method("PROPFIND", "".toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw IllegalStateException("坚果云认证失败(401)：请确认使用的是第三方应用密码")
            }
            if (response.code == 403) {
                throw IllegalStateException("坚果云无权限(403)：请检查应用密码与 WebDAV 权限")
            }
            if (!response.isSuccessful && response.code != 207) {
                throw IllegalStateException("坚果云读取同步目录失败 HTTP ${response.code}")
            }
            val xml = response.body?.string().orEmpty()
            val hrefRegex = Regex(
                "<(?:[A-Za-z0-9_]+:)?href\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?href>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            return hrefRegex.findAll(xml).mapNotNull { match ->
                val href = match.groupValues[1].trim()
                if (href.endsWith("/")) return@mapNotNull null
                val name = href.substringAfterLast('/')
                if (!name.startsWith(HISTORY_FILE_PREFIX) || !name.endsWith(".json")) return@mapNotNull null
                try { URLDecoder.decode(name, "UTF-8") } catch (t: Throwable) { name }
            }.toList()
        }
    }

    /** 删除单个远端文件；文件不存在视为已删除。 */
    fun delete(fileName: String) {
        val request = Request.Builder()
            .url(fileUrl(fileName))
            .header("Authorization", authHeader)
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 200 || response.code == 204) return
            if (response.code == 401) {
                throw IllegalStateException("坚果云认证失败(401)：请确认使用的是第三方应用密码")
            }
            if (response.code == 403) {
                throw IllegalStateException("坚果云无权限(403)：请检查应用密码与 WebDAV 权限")
            }
            throw IllegalStateException("坚果云删除同步文件失败 HTTP ${response.code}")
        }
    }

    private fun encodeSegment(value: String): String {
        val hex = "0123456789ABCDEF"
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val int = byte.toInt() and 0xFF
            val unreserved = int in 0x41..0x5A || int in 0x61..0x7A || int in 0x30..0x39 ||
                int == 0x2D || int == 0x2E || int == 0x5F || int == 0x7E
            if (unreserved) out.append(int.toChar())
            else {
                out.append('%')
                out.append(hex[int shr 4])
                out.append(hex[int and 0x0F])
            }
        }
        return out.toString()
    }

    companion object {
        const val HISTORY_FILE_PREFIX = "avbox-history-"
        private const val TIMEOUT_SEC = 15L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
