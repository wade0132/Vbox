package com.github.VVbox.osc.ui

import androidx.activity.compose.setContent
import com.github.VVbox.osc.R
import com.github.VVbox.osc.ui.base.BaseActivity
import com.github.VVbox.osc.ui.components.SheetHostScaffold
import com.github.VVbox.osc.ui.theme.AVBoxTheme
import com.github.VVbox.osc.ui.theme.enableTransparentEdgeToEdge

class SearchActivity : BaseActivity() {

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    SearchScreen()
                }
            }
        }
    }
}
