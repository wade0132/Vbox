package com.github.VVbox.osc.ui

import android.content.Context
import android.content.Intent
import com.github.VVbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import com.github.VVbox.osc.R
import com.github.VVbox.osc.ui.base.BaseActivity
import com.github.VVbox.osc.ui.components.SheetHostScaffold
import com.github.VVbox.osc.ui.page.ThemeSettingsScreen
import com.github.VVbox.osc.ui.theme.AVBoxTheme

class ThemeSettingsActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ThemeSettingsActivity::class.java))
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
                    ThemeSettingsScreen(onNavigateBack = { finish() })
                }
            }
        }
    }
}
