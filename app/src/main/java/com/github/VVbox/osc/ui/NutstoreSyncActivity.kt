package com.github.VVbox.osc.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.ComposeView
import com.github.VVbox.osc.R
import com.github.VVbox.osc.ui.base.BaseActivity
import com.github.VVbox.osc.ui.components.SheetHostScaffold
import com.github.VVbox.osc.ui.page.NutstoreSyncScreen
import com.github.VVbox.osc.ui.theme.AVBoxTheme
import com.github.VVbox.osc.ui.theme.enableTransparentEdgeToEdge

class NutstoreSyncActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, NutstoreSyncActivity::class.java))
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    NutstoreSyncScreen(onNavigateBack = { finish() })
                }
            }
        }
    }
}
