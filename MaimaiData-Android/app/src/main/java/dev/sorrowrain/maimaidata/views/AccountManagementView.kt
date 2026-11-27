package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.sorrowrain.maimaidata.components.AppCapsuleLabel
import dev.sorrowrain.maimaidata.components.AppCard
import dev.sorrowrain.maimaidata.components.AppConfirmDialog
import dev.sorrowrain.maimaidata.components.AppErrorDialog
import dev.sorrowrain.maimaidata.components.AppInlineButton
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppInsetRow
import dev.sorrowrain.maimaidata.components.AppPrimaryButton
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.CapsuleStyle
import dev.sorrowrain.maimaidata.components.StatusToastHost
import dev.sorrowrain.maimaidata.components.appInsetRowHighlight
import dev.sorrowrain.maimaidata.components.rememberToastState
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme

@Composable
fun AccountManagementScreen(
    onBack: () -> Unit,
    onOpenIdentityDetail: (String) -> Unit
) {
    val store = LocalDataStore.current
    val toastState = rememberToastState()

    var showAddSheet by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var warningMessage by remember { mutableStateOf<String?>(null) }
    val isMultiProfile = store.identities.size > 1

    AppPageBackground {
        Scaffold(
            topBar = {
                AppTopBar(title = "成绩档案", onBack = onBack)
            },
            containerColor = androidx.compose.ui.graphics.Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
                ) {
                    if (store.credentialStoreLoadFailed) {
                        AppCard(
                            padding = AppTheme.cardPadding
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                            ) {
                                androidx.compose.foundation.layout.Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                                ) {
                                    Text(
                                        text = "安全存储读取受限",
                                        style = AppTheme.AppTypography.songTitle,
                                        color = AppTheme.warningText,
                                        modifier = Modifier.weight(1f)
                                    )
                                    AppInlineButton(
                                        title = "重置档案",
                                        tint = AppTheme.destructive,
                                        enabled = !store.isSyncingRecords,
                                        onClick = { showResetConfirm = true }
                                    )
                                }
                                Text(
                                    text = "当前未能解码或读取安全存储中的玩家档案。为避免数据被覆盖，已暂时锁定修改操作；请重启应用后重试，如仍无法恢复可点击重置档案。",
                                    style = AppTheme.AppTypography.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    AppSectionCard(title = "成绩档案") {
                        if (store.identities.isEmpty()) {
                            AppInsetGroup(padding = AppTheme.cardPadding) {
                                Text(
                                    text = "还没有成绩档案，点击下方按钮添加",
                                    style = AppTheme.AppTypography.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            AppInsetGroup {
                                store.identities.forEachIndexed { idx, identity ->
                                    if (idx > 0) {
                                        AppInsetDivider()
                                    }
                                    val isCurrent = isMultiProfile && (identity.id == store.activeIdentityId)
                                    val showSwitchButton = isMultiProfile && (identity.id != store.activeIdentityId)

                                    AppInsetRow(
                                        title = identity.primaryDisplayName,
                                        showsChevron = true,
                                        modifier = Modifier
                                            .appInsetRowHighlight(isCurrent)
                                            .semantics {
                                                if (isCurrent) {
                                                    selected = true
                                                }
                                            },
                                        onClick = { onOpenIdentityDetail(identity.id) },
                                        trailing = when {
                                            isCurrent -> {
                                                {
                                                    AppCapsuleLabel(text = "当前", style = CapsuleStyle.Compact)
                                                }
                                            }

                                            showSwitchButton -> {
                                                {
                                                    AppInlineButton(
                                                        title = "设为当前",
                                                        enabled = !store.isSyncingRecords,
                                                        modifier = Modifier.semantics {
                                                            contentDescription = "将 ${identity.primaryDisplayName} 设为当前档案"
                                                        },
                                                        onClick = {
                                                            store.switchIdentity(identity.id)
                                                            toastState.show("已切换至 ${identity.primaryDisplayName}")
                                                        }
                                                    )
                                                }
                                            }

                                            else -> null
                                        }
                                    )
                                }
                            }
                        }
                    }

                    AppPrimaryButton(
                        title = "添加档案",
                        icon = Icons.Rounded.AddCircle,
                        enabled = !store.isSyncingRecords && !store.credentialStoreLoadFailed,
                        onClick = { showAddSheet = true }
                    )
                }

                StatusToastHost(
                    toastState = toastState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    if (showAddSheet) {
        AddIdentitySheet(
            onDismiss = { showAddSheet = false },
            onCompleted = { msg -> toastState.show(msg, durationMs = 2000L) },
            onWarningMessage = { msg -> warningMessage = msg }
        )
    }

    if (showResetConfirm) {
        AppConfirmDialog(
            title = "重置安全存储档案",
            message = "将清空无法解码的旧档案索引记录，以便重新添加账号。此操作无法撤销。",
            confirmText = "重置档案",
            isDestructive = true,
            onConfirm = {
                showResetConfirm = false
                store.resetCredentialStoreAfterLoadFailure()
                toastState.show("已重置档案存储，请重新绑定账号", durationMs = 2200L)
            },
            onDismiss = { showResetConfirm = false }
        )
    }

    AppErrorDialog(
        title = "档案已添加",
        message = warningMessage,
        onDismiss = { warningMessage = null }
    )
}
