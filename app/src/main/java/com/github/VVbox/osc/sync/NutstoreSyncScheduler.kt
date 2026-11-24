package com.github.VVbox.osc.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import com.github.VVbox.osc.api.AppBootstrap
import com.github.VVbox.osc.util.AppLog
import com.github.VVbox.osc.util.HistoryHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 观影记录自动同步调度器。
 *
 * 触发源：
 *  - 冷启动（Application.onCreate）
 *  - 应用回到前台（含从后台「打开 App」，这是旧实现缺失的主因）
 *  - 定时轮询（间隔可在设置中调整）
 *  - 网络恢复（之前因无网络失败时自动补做）
 *  - 刚开启同步 / 补齐账号等配置变更
 *
 * 可靠性：
 *  - 等订阅与 CID 就绪后才开始，避免读到错误的云端文件
 *  - 所有触发合并到一条串行通道，重复触发「至少再跑一次」而不是相互撞掉
 *  - 失败按指数退避重试，认证/配置类错误不重试
 *  - 无网络时标记待补，联网后自动重试
 *  - 结果通过 [SyncEvents] 通知界面，并写入配置供「上次自动同步」展示
 */
object NutstoreSyncScheduler {

    const val REASON_COLD_START = "cold_start"
    const val REASON_FOREGROUND = "foreground"
    const val REASON_POLL = "poll"
    const val REASON_NETWORK = "network"
    const val REASON_CONFIG = "config"

    private const val TAG = "nutstore-auto"
    private const val READY_TIMEOUT_MS = 20_000L
    private const val FOREGROUND_MIN_INTERVAL_MS = 30_000L
    private const val SUCCESS_MIN_INTERVAL_MS = 10_000L
    private val RETRY_DELAYS = longArrayOf(3_000L, 12_000L, 40_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)

    @Volatile private var pending = false
    @Volatile private var pendingReason = ""
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var lastSuccessAt = 0L
    @Volatile private var foregroundCount = 0
    @Volatile private var installed = false
    private var appContext: Context? = null
    private var pollJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 在 Application.onCreate 调用一次：安装生命周期/网络监听并启动冷启动同步。 */
    fun install(app: Application) {
        if (installed) return
        installed = true
        appContext = app.applicationContext
        start()
        registerForeground(app)
        registerNetwork(app)
        requestSync(REASON_COLD_START, force = true)
        AppLog.i("$TAG installed")
    }

    /** 保留原 API：启动定时轮询。 */
    @Synchronized
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val minutes = NutstoreSyncConfig.load().intervalMinutes.coerceIn(5, 720)
                delay(minutes * 60_000L)
                val config = NutstoreSyncConfig.load()
                if (config.enabled && config.autoSync && config.isConfigured()) {
                    requestSync(REASON_POLL, force = true)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 统一同步入口：重复触发合并成「至少再跑一次」，不会并发也不会被丢弃。 */
    fun requestSync(reason: String, force: Boolean = false) {
        if (!force) {
            val config = NutstoreSyncConfig.load()
            if (!config.enabled || !config.isConfigured()) return
            if (reason == REASON_FOREGROUND && !config.syncOnOpen) return
            val now = System.currentTimeMillis()
            if (now - lastAttemptAt < FOREGROUND_MIN_INTERVAL_MS) return
            if (now - lastSuccessAt < SUCCESS_MIN_INTERVAL_MS) return
        }
        scope.launch { drain(reason) }
    }

    /** 配置变更（例如刚开启同步、补齐账号）后调用：立即尝试一轮。 */
    fun onConfigChanged() = requestSync(REASON_CONFIG, force = true)

    private suspend fun drain(reason: String) {
        if (!inFlight.compareAndSet(false, true)) {
            pending = true
            pendingReason = reason
            return
        }
        try {
            var current = reason
            do {
                pending = false
                runWithRetry(current)
                current = pendingReason
            } while (pending)
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun runWithRetry(reason: String) {
        if (!awaitReady()) {
            // 超时也继续尝试：KV 里的 CID 通常已经可用，总比完全不同步好。
            AppLog.i("$TAG ready wait timeout, continue anyway")
        }
        var attempt = 0
        while (attempt <= RETRY_DELAYS.size) {
            val config = NutstoreSyncConfig.load()
            if (!canSync(config)) {
                SyncEvents.post(
                    SyncNotice(false, "同步未开启或坚果云账号未配置", reason = reason)
                )
                return
            }
            if (!isNetworkAvailable()) {
                NutstoreSyncConfig.save(NutstoreSyncConfig.load().copy(pendingSync = true))
                SyncEvents.post(
                    SyncNotice(false, "暂无网络，联网后会自动重试", reason = reason)
                )
                return
            }
            lastAttemptAt = System.currentTimeMillis()
            NutstoreSyncConfig.save(
                NutstoreSyncConfig.load().copy(lastAttemptAt = lastAttemptAt)
            )
            val result = try {
                WatchHistorySync.syncNow()
            } catch (t: Throwable) {
                WatchHistorySync.Result(false, t.message ?: t.javaClass.simpleName)
            }
            if (result.ok) {
                lastSuccessAt = System.currentTimeMillis()
                val message = "已同步 ${result.records} 条观影记录"
                NutstoreSyncConfig.save(
                    NutstoreSyncConfig.load().copy(
                        pendingSync = false,
                        lastAutoMessage = message,
                        lastAutoOk = true,
                    )
                )
                SyncEvents.post(
                    SyncNotice(true, "同步完成，$message", result.records, reason = reason)
                )
                AppLog.i("$TAG success reason=$reason records=${result.records}")
                return
            }
            val retryable = isRetryable(result.message)
            if (!retryable || attempt == RETRY_DELAYS.size) {
                NutstoreSyncConfig.save(
                    NutstoreSyncConfig.load().copy(
                        pendingSync = retryable,
                        lastAutoMessage = result.message,
                        lastAutoOk = false,
                    )
                )
                SyncEvents.post(
                    SyncNotice(false, "同步失败：${result.message}", reason = reason)
                )
                AppLog.i("$TAG failed reason=$reason msg=${result.message}")
                return
            }
            SyncEvents.post(
                SyncNotice(
                    false,
                    "同步重试中（${attempt + 1}/${RETRY_DELAYS.size + 1}）：${result.message}",
                    reason = reason,
                    final = false,
                )
            )
            delay(RETRY_DELAYS[attempt])
            attempt++
        }
    }

    /** 等订阅配置加载完成，确保 CurrentSubscription.cid() 已稳定。 */
    private suspend fun awaitReady(): Boolean = withTimeoutOrNull(READY_TIMEOUT_MS) {
        AppBootstrap.state.first { it !is AppBootstrap.Boot.Loading }
        true
    } ?: false

    private fun canSync(config: NutstoreSyncConfig): Boolean =
        config.enabled && config.isConfigured() && !HistoryHelper.isIncognito()

    private fun isRetryable(message: String): Boolean {
        val s = message.lowercase()
        return !s.contains("401") &&
            !s.contains("403") &&
            !s.contains("应用密码") &&
            !s.contains("认证") &&
            !s.contains("未配置") &&
            !s.contains("未开启") &&
            !s.contains("https") &&
            !s.contains("隐身")
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 0→1 表示应用回到前台；从后台「打开 App」走的就是这条路径。 */
    private fun registerForeground(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (foregroundCount == 0) {
                    scope.launch {
                        delay(400L)
                        requestSync(REASON_FOREGROUND)
                    }
                }
                foregroundCount++
            }

            override fun onActivityStopped(activity: Activity) {
                foregroundCount = (foregroundCount - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** 之前因无网络挂起的同步，在联网后自动补做。 */
    private fun registerNetwork(app: Application) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (NutstoreSyncConfig.load().pendingSync) {
                    AppLog.i("$TAG network available, retry pending sync")
                    requestSync(REASON_NETWORK, force = true)
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (t: Throwable) {
            AppLog.i("$TAG registerNetworkCallback failed: ${t.message}")
        }
    }
}
