package com.github.vying.osc.ui.page

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.vying.osc.R
import com.github.vying.osc.ui.components.AppTopBarScaffold
import com.github.vying.osc.ui.components.SettingsCard
import com.github.vying.osc.ui.components.SettingsCardPosition
import com.github.vying.osc.ui.components.SettingsGroup
import com.github.vying.osc.ui.components.SettingsSwitchRow
import com.github.vying.osc.ui.components.SettingsTestButton
import com.github.vying.osc.ui.components.SettingsTextFieldRow
import com.github.vying.osc.ui.components.TopBarActionBox
import com.github.vying.osc.util.restartApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardContentPadding = 20.dp
private val FieldVerticalPadding = 4.dp

private fun formatSyncTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

@Composable
fun NutstoreSyncScreen(
    onNavigateBack: () -> Unit,
    viewModel: NutstoreSyncViewModel = viewModel(),
) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsState()
    val result by viewModel.result.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val configResult by viewModel.configResult.collectAsState()
    val configBusy by viewModel.configBusy.collectAsState()
    val autoStatus by viewModel.autoStatus.collectAsState()
    val listState = rememberScrollState()
    LaunchedEffect(Unit) {
        // 所有操作（同步 / 备份 / 恢复 / 清除，含后台自动同步）完成后统一弹提示。
        viewModel.notice.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showClearConfigConfirm by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    val status = when {
        result == null -> stringResource(R.string.nutstore_sync_idle)
        result?.ok == true && result?.showMessage != true ->
            stringResource(R.string.nutstore_sync_success, result?.records ?: 0)
        else -> result?.message.orEmpty()
    }
    val configStatus = configResult?.message.orEmpty()
    val autoSuccessText = stringResource(R.string.common_success)
    val autoFailedText = stringResource(R.string.nutstore_sync_auto_failed)
    val autoText = when {
        autoStatus.at == 0L -> stringResource(R.string.nutstore_sync_auto_never)
        autoStatus.pending -> stringResource(R.string.nutstore_sync_auto_pending)
        else -> stringResource(
            R.string.nutstore_sync_auto_status,
            formatSyncTime(autoStatus.at),
            autoStatus.message.ifBlank { if (autoStatus.ok) autoSuccessText else autoFailedText },
        )
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.nutstore_sync_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back), onClick = onNavigateBack)
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Spacer(Modifier.height(topPad + 8.dp))
            Text(
                text = stringResource(R.string.nutstore_sync_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            SettingsGroup(title = stringResource(R.string.nutstore_sync_group_basic)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.nutstore_sync_enable),
                        subtitle = stringResource(R.string.nutstore_sync_enable_sub),
                        checked = config.enabled,
                        onCheckedChange = { checked -> viewModel.update { it.copy(enabled = checked) } },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.nutstore_sync_auto),
                        subtitle = stringResource(R.string.nutstore_sync_auto_sub),
                        checked = config.autoSync,
                        onCheckedChange = { checked -> viewModel.update { it.copy(autoSync = checked) } },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.nutstore_sync_on_open),
                        subtitle = stringResource(R.string.nutstore_sync_on_open_sub),
                        checked = config.syncOnOpen,
                        onCheckedChange = { checked -> viewModel.update { it.copy(syncOnOpen = checked) } },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SettingsGroup(title = stringResource(R.string.nutstore_sync_group_account)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsTextFieldRow(
                        label = stringResource(R.string.nutstore_sync_server),
                        value = config.server,
                        placeholder = stringResource(R.string.nutstore_sync_server_hint),
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = FieldVerticalPadding),
                        onValueChange = { value -> viewModel.update { it.copy(server = value) } },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsTextFieldRow(
                        label = stringResource(R.string.nutstore_sync_username),
                        value = config.username,
                        placeholder = stringResource(R.string.nutstore_sync_username_hint),
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = FieldVerticalPadding),
                        onValueChange = { value -> viewModel.update { it.copy(username = value) } },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsTextFieldRow(
                        label = stringResource(R.string.nutstore_sync_password),
                        value = config.password,
                        placeholder = stringResource(R.string.nutstore_sync_password_hint),
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = FieldVerticalPadding),
                        masked = true,
                        onValueChange = { value -> viewModel.update { it.copy(password = value) } },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsTextFieldRow(
                        label = stringResource(R.string.nutstore_sync_folder),
                        value = config.folder,
                        placeholder = stringResource(R.string.nutstore_sync_folder_hint),
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = FieldVerticalPadding),
                        onValueChange = { value -> viewModel.update { it.copy(folder = value) } },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SettingsTestButton(
                text = stringResource(R.string.nutstore_sync_now),
                loading = syncing,
                onClick = { viewModel.syncNow() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = autoText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            SettingsGroup(title = stringResource(R.string.nutstore_sync_group_config)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    Text(
                        text = stringResource(R.string.nutstore_sync_config_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = 12.dp),
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsTestButton(
                        text = stringResource(R.string.nutstore_sync_config_backup),
                        enabled = !configBusy && !syncing,
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = 8.dp),
                        onClick = { viewModel.backupConfig() },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsTestButton(
                        text = stringResource(R.string.nutstore_sync_config_restore),
                        enabled = !configBusy && !syncing,
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = 8.dp),
                        onClick = { showRestoreConfirm = true },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsTestButton(
                        text = stringResource(R.string.nutstore_sync_config_clear),
                        enabled = !configBusy && !syncing,
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = 8.dp),
                        onClick = { showClearConfigConfirm = true },
                    )
                }
            }
            if (configStatus.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = configStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))

            SettingsGroup(title = stringResource(R.string.nutstore_sync_group_danger)) {
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsTestButton(
                        text = stringResource(R.string.nutstore_sync_clear_cloud),
                        enabled = !syncing && !configBusy,
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = 8.dp),
                        onClick = { showClearConfirm = true },
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(text = stringResource(R.string.nutstore_sync_clear_cloud_title)) },
            text = { Text(text = stringResource(R.string.nutstore_sync_clear_cloud_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearRemoteHistory()
                }) {
                    Text(text = stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(text = stringResource(R.string.nutstore_sync_config_restore_title)) },
            text = { Text(text = stringResource(R.string.nutstore_sync_config_restore_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    viewModel.restoreConfig(onSuccess = { showRestartConfirm = true })
                }) {
                    Text(text = stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showClearConfigConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfigConfirm = false },
            title = { Text(text = stringResource(R.string.nutstore_sync_config_clear_title)) },
            text = { Text(text = stringResource(R.string.nutstore_sync_config_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfigConfirm = false
                    viewModel.clearRemoteConfig()
                }) {
                    Text(text = stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfigConfirm = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text(text = stringResource(R.string.nutstore_sync_config_restart_title)) },
            text = { Text(text = stringResource(R.string.nutstore_sync_config_restart_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestartConfirm = false
                    restartApp(context)
                }) {
                    Text(text = stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirm = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
