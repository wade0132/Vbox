package com.github.VVbox.osc.base

import com.github.VVbox.osc.sync.NutstoreSyncScheduler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.os.SystemClock

import com.github.catvod.crawler.JsLoader
import com.github.VVbox.osc.BuildConfig
import com.github.VVbox.osc.boot.BootGuard
import com.github.VVbox.osc.data.AppDataManager
import com.github.VVbox.osc.io.FileUtils
import com.github.VVbox.osc.net.HttpClients
import com.github.VVbox.osc.server.ControlManager
import com.github.VVbox.osc.util.AppContextHolder
import com.github.VVbox.osc.util.ActivityStack
import com.github.VVbox.osc.util.EpgUtil
import com.github.VVbox.osc.util.KVKeys
import com.github.VVbox.osc.util.KV
import com.github.VVbox.osc.util.AppLog
import com.github.VVbox.osc.util.LanguageManager
import com.github.VVbox.osc.util.Probe
import com.github.VVbox.osc.util.VodImages
import com.whl.quickjs.android.QuickJSLoader

import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.unit.Subunits

class App : Application() {

    override fun attachBaseContext(base: Context) {
        Probe.step("kvInit") { KV.init(base) }
        super.attachBaseContext(LanguageManager.wrap(base))
    }

    private var bootLastMs = 0L

    private fun bootMark(step: String) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        AppLog.i("echo-boot $step dt=" + (now - bootLastMs) + "ms")
        bootLastMs = now
    }

    override fun onCreate() {
        super.onCreate()
        bootLastMs = SystemClock.elapsedRealtime()
        installStrictMode()
        bootMark("installStrictMode")
        instance = this
        AppContextHolder.install(this)
        BootGuard.install()
        bootMark("bootGuard")
        initParams()
        bootMark("initParams")
        Probe.trace("boot:httpClients") { HttpClients.init() }
        bootMark("httpClients")
        Probe.trace("boot:epgUtil") { EpgUtil.init() }
        bootMark("epgUtil")
        Probe.trace("boot:controlManager") { ControlManager.init(this) }
        bootMark("controlManager")
        Probe.trace("boot:appDataManager") { AppDataManager.init() }
        // 安装自动同步：冷启动 + 回前台 + 定时 + 网络恢复补做（旧实现只在冷启动跑一次）。
        NutstoreSyncScheduler.install(this)
        bootMark("appDataManager")
        Probe.trace("boot:autoSize") {
            AutoSizeConfig.getInstance().setCustomFragment(true).unitsManager
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM)
        }
        bootMark("autoSize")
        Probe.trace("boot:quickJSLoader") { loadQuickJs() }
        bootMark("quickJSLoader")
        Probe.trace("boot:vodImages") { VodImages.init() }
        bootMark("vodImages")
        Thread({
            FileUtils.ensureNoMediaMarker()
            Probe.trace("boot:cleanPlayerCache") { FileUtils.cleanPlayerCache() }
        }, "exo-cache-boot").start()
        bootMark("onCreateDone")
    }

    private fun loadQuickJs() {
        val previous = StrictMode.allowThreadDiskReads()
        try {
            QuickJSLoader.init()
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }

    private fun initParams() {
        KV.put(KVKeys.PLAYER_IS_LIVE, false)
        if (!KV.contains(KVKeys.PLAY_TYPE)) {
            KV.put(KVKeys.PLAY_TYPE, 2)
        } else {
            val playType = KV.get(KVKeys.PLAY_TYPE, 2)
            if (playType == 0 || playType == 1 || (playType > 2 && playType != 13)) {
                KV.put(KVKeys.PLAY_TYPE, 2)
            }
        }
    }

    private fun installStrictMode() {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .penaltyLog()
                .build()
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        JsLoader.destroy()
    }

    fun getCurrentActivity(): Activity {
        return ActivityStack.getInstance().currentActivity()
    }


    companion object {
        private var instance: App? = null

        var dashData: String? = null

        @JvmStatic
        fun getInstance(): App? {
            return instance
        }
    }
}
