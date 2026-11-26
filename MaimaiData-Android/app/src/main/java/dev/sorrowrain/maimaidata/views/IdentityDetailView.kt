package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import dev.sorrowrain.maimaidata.components.AppCapsuleLabel
import dev.sorrowrain.maimaidata.components.AppCard
import dev.sorrowrain.maimaidata.components.AppConfirmDialog
import dev.sorrowrain.maimaidata.components.AppErrorDialog
import dev.sorrowrain.maimaidata.components.AppInlineButton
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppInsetRow
import dev.sorrowrain.maimaidata.components.AppSecondaryButton
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppTextField
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.CapsuleStyle
import dev.sorrowrain.maimaidata.components.StatusToastHost
import dev.sorrowrain.maimaidata.components.rememberToastState
import dev.sorrowrain.maimaidata.models.ChannelBindingStatus
import dev.sorrowrain.maimaidata.models.PlayerIdentity
import dev.sorrowrain.maimaidata.models.SyncChannelKind
import dev.sorrowrain.maimaidata.models.SyncOperationKind
import dev.sorrowrain.maimaidata.models.SyncPlan
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.services.MaimaiAPIException
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun IdentityDetailScreen(
    identityId: String,
    onBack: () -> Unit
) {
    val store = LocalDataStore.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val toastState = rememberToastState()

    val normalizedId = identityId.lowercase(Locale.ROOT)
    val identity = store.identities.firstOrNull { it.id == normalizedId }
    val isMultiProfile = store.identities.size > 1
    val isCurrent = store.activeIdentityId == normalizedId

    var rebindChannel by remember { mutableStateOf<SyncChannelKind?>(null) }
    var showFullSyncSheet by remember { mutableStateOf(false) }
    var pendingUnbindChannel by remember { mutableStateOf<SyncChannelKind?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var alertTitle by remember { mutableStateOf("操作提示") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var b50NameDraft by remember(normalizedId) {
        mutableStateOf(identity?.customNickname.orEmpty())
    }
    val latestDraft by rememberUpdatedState(b50NameDraft)

    DisposableEffect(normalizedId) {
        onDispose {
            store.updateCustomNickname(latestDraft, normalizedId)
        }
    }

    LaunchedEffect(identity == null) {
        if (identity == null) {
            onBack()
        }
    }

    fun runAction(failureTitle: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: MaimaiAPIException) {
                alertTitle = failureTitle
                errorMessage = err.recoveryMessage
            } catch (err: Throwable) {
                alertTitle = failureTitle
                errorMessage = err.message ?: "操作失败，请稍后重试。"
            }
        }
    }

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(title = "档案详情", onBack = onBack)
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (identity != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
                    ) {
                        // 1. Summary Card
                        AppCard(padding = AppTheme.cardPadding, tint = AppTheme.accent) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                                ) {
                                    Text(
                                        text = identity.primaryDisplayName,
                                        style = AppTheme.AppTypography.cardTitle,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (isMultiProfile) {
                                        if (isCurrent) {
                                            AppCapsuleLabel(text = "当前", style = CapsuleStyle.Compact)
                                        } else {
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
                                }

                                Text(
                                    text = identity.lastSyncText() ?: "尚未同步",
                                    style = AppTheme.AppTypography.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 2. Sync Section
                        AppSectionCard(title = "同步成绩") {
                            if (!identity.hasAnyChannel) {
                                AppInsetGroup(padding = AppTheme.cardPadding) {
                                    Text(
                                        text = "先在下方绑定一个账号",
                                        style = AppTheme.AppTypography.caption,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                AppInsetGroup {
                                    when (val plan = identity.syncPlan) {
                                        SyncPlan.CNDirectQuick -> {
                                            val quickLoading = store.isSyncing(identity.id, SyncOperationKind.QUICK_SYNC)
                                            val fullLoading = store.isSyncing(identity.id, SyncOperationKind.FULL_SYNC)
                                            AppInsetRow(
                                                title = "快速同步",
                                                subtitle = "仅更新达成率与 DX 分数",
                                                trailing = {
                                                    AppInlineButton(
                                                        title = "同步",
                                                        isLoading = quickLoading,
                                                        enabled = !store.isSyncingRecords && !store.isLoadingLocalData && store.isCNDirectAvailable,
                                                        modifier = Modifier.semantics {
                                                            contentDescription = if (quickLoading) "正在同步" else "快速同步"
                                                        },
                                                        onClick = {
                                                            runAction("同步失败") {
                                                                store.quickSyncSelfHosted(identityId = identity.id, writeback = false)
                                                                toastState.show("成绩已同步")
                                                            }
                                                        }
                                                    )
                                                }
                                            )
                                            AppInsetDivider()
                                            AppInsetRow(
                                                title = "完整同步",
                                                subtitle = "更新全量数据，需获取玩家二维码",
                                                trailing = {
                                                    AppInlineButton(
                                                        title = "同步",
                                                        isLoading = fullLoading,
                                                        enabled = !store.isSyncingRecords && !store.isLoadingLocalData && store.isCNDirectAvailable,
                                                        modifier = Modifier.semantics {
                                                            contentDescription = if (fullLoading) "正在同步" else "完整同步"
                                                        },
                                                        onClick = { showFullSyncSheet = true }
                                                    )
                                                }
                                            )
                                        }

                                        is SyncPlan.SingleProber -> when (plan.channel) {
                                            SyncChannelKind.LXNS -> {
                                                val loading = store.isSyncing(identity.id, SyncOperationKind.PULL_LXNS)
                                                AppInsetRow(
                                                    title = "同步成绩",
                                                    subtitle = "从落雪咖啡屋获取",
                                                    trailing = {
                                                        AppInlineButton(
                                                            title = "同步",
                                                            isLoading = loading,
                                                            enabled = !store.isSyncingRecords && !store.isLoadingLocalData,
                                                            modifier = Modifier.semantics {
                                                                contentDescription = if (loading) "正在同步" else "同步成绩"
                                                            },
                                                            onClick = {
                                                                runAction("同步失败") {
                                                                    store.pullFromLXNS(identityId = identity.id)
                                                                    toastState.show("成绩已同步")
                                                                }
                                                            }
                                                        )
                                                    }
                                                )
                                            }

                                            SyncChannelKind.DIVING_FISH -> {
                                                val loading = store.isSyncing(identity.id, SyncOperationKind.PULL_DIVING_FISH)
                                                AppInsetRow(
                                                    title = "同步成绩",
                                                    subtitle = "从水鱼查分器获取",
                                                    trailing = {
                                                        AppInlineButton(
                                                            title = "同步",
                                                            isLoading = loading,
                                                            enabled = !store.isSyncingRecords && !store.isLoadingLocalData,
                                                            modifier = Modifier.semantics {
                                                                contentDescription = if (loading) "正在同步" else "同步成绩"
                                                            },
                                                            onClick = {
                                                                runAction("同步失败") {
                                                                    store.pullFromDivingFish(identityId = identity.id)
                                                                    toastState.show("成绩已同步")
                                                                }
                                                            }
                                                        )
                                                    }
                                                )
                                            }

                                            SyncChannelKind.SELF_HOSTED -> Unit
                                        }

                                        SyncPlan.MergedProbers -> {
                                            val loading = store.isSyncing(identity.id, SyncOperationKind.PULL_MERGED)
                                            AppInsetRow(
                                                title = "同步成绩",
                                                subtitle = "拉取并合并落雪与水鱼的最佳成绩",
                                                trailing = {
                                                    AppInlineButton(
                                                        title = "同步",
                                                        isLoading = loading,
                                                        enabled = !store.isSyncingRecords && !store.isLoadingLocalData,
                                                        modifier = Modifier.semantics {
                                                            contentDescription = if (loading) "正在同步" else "合并同步成绩"
                                                        },
                                                        onClick = {
                                                            runAction("同步失败") {
                                                                val outcome = store.pullMergedProbers(identityId = identity.id)
                                                                if (outcome.warningMessage != null) {
                                                                    alertTitle = "部分同步成功"
                                                                    errorMessage = outcome.warningMessage
                                                                } else {
                                                                    toastState.show("成绩已同步")
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                            )
                                        }

                                        SyncPlan.None -> Unit
                                    }

                                    if (identity.canUploadToProbers) {
                                        val uploadLoading = store.isSyncing(identity.id, SyncOperationKind.WRITEBACK)
                                        AppInsetDivider()
                                        AppInsetRow(
                                            title = "上传到查分器",
                                            subtitle = "将本地成绩同步至查分器",
                                            trailing = {
                                                AppInlineButton(
                                                    title = "上传",
                                                    tint = AppTheme.Palette.statFC,
                                                    isLoading = uploadLoading,
                                                    enabled = !store.isSyncingRecords && !store.isLoadingLocalData,
                                                    modifier = Modifier.semantics {
                                                        contentDescription = if (uploadLoading) "正在上传" else "上传到查分器"
                                                    },
                                                    onClick = {
                                                        runAction("上传失败") {
                                                            val summary = store.writebackToBoundProbers(identityId = identity.id)
                                                            when {
                                                                summary.results.isEmpty() -> toastState.show("未绑定可上传的查分器")
                                                                summary.isAllSucceeded -> toastState.show("上传成功")
                                                                else -> {
                                                                    alertTitle = if (summary.hasAnySucceeded) "部分上传失败" else "上传失败"
                                                                    errorMessage = summary.summaryText
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 3. Channels Section
                        AppSectionCard(title = "账号绑定") {
                            AppInsetGroup {
                                SyncChannelKind.entries.forEachIndexed { idx, channel ->
                                    if (idx > 0) {
                                        AppInsetDivider()
                                    }
                                    ChannelBindingRow(
                                        channel = channel,
                                        identity = identity,
                                        cnDirectAvailable = store.isCNDirectAvailable,
                                        controlsDisabled = store.isSyncingRecords || store.credentialStoreLoadFailed,
                                        onBind = { rebindChannel = channel },
                                        onRequestUnbind = { pendingUnbindChannel = channel }
                                    )
                                }
                            }
                        }

                        // 4. B50 Image Player Name
                        AppSectionCard(title = "B50 图片玩家名称") {
                            AppTextField(
                                value = b50NameDraft,
                                onValueChange = { if (!store.credentialStoreLoadFailed) b50NameDraft = it },
                                placeholder = identity.primaryDisplayName,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (store.credentialStoreLoadFailed) 0.55f else 1f),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        store.updateCustomNickname(b50NameDraft, identity.id)
                                        focusManager.clearFocus()
                                    }
                                )
                            )
                        }

                        // 5. Delete Profile
                        AppSecondaryButton(
                            title = "移除此档案",
                            tint = AppTheme.destructive,
                            enabled = !store.isSyncingRecords && !store.credentialStoreLoadFailed,
                            onClick = { showDeleteConfirm = true }
                        )
                    }
                }

                StatusToastHost(
                    toastState = toastState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    rebindChannel?.let { channel ->
        ChannelBindSheet(
            channel = channel,
            targetIdentityId = normalizedId,
            onDismiss = { rebindChannel = null },
            onCompleted = { msg -> toastState.show(msg, durationMs = 2000L) },
            onWarningMessage = { msg ->
                alertTitle = "档案已更新"
                errorMessage = msg
            }
        )
    }

    if (showFullSyncSheet && identity != null) {
        FullSyncSheet(
            identity = identity,
            onDismiss = { showFullSyncSheet = false },
            onCompleted = { msg ->
                showFullSyncSheet = false
                toastState.show(msg, durationMs = 2200L)
            }
        )
    }

    pendingUnbindChannel?.let { channel ->
        val message = if (channel == SyncChannelKind.SELF_HOSTED) {
            "解除后，本地缓存成绩会保留；再次绑定需要重新获取微信玩家二维码。"
        } else {
            "确定要解除此档案的 ${channel.displayName} 绑定吗？本地缓存成绩会保留。"
        }
        AppConfirmDialog(
            title = "解除 ${channel.displayName} 绑定",
            message = message,
            confirmText = "解除绑定",
            isDestructive = true,
            onConfirm = {
                pendingUnbindChannel = null
                val target = identity ?: return@AppConfirmDialog
                runAction("解除绑定失败") {
                    store.unbindChannel(channel, target.id)
                    toastState.show("已解绑 ${channel.displayName}")
                }
            },
            onDismiss = { pendingUnbindChannel = null }
        )
    }

    if (showDeleteConfirm && identity != null) {
        AppConfirmDialog(
            title = "移除此档案",
            message = "确定要移除玩家档案「${identity.primaryDisplayName}」、已绑定凭据及本地缓存成绩吗？",
            confirmText = "移除",
            isDestructive = true,
            onConfirm = {
                showDeleteConfirm = false
                store.removeIdentity(identity.id)
                onBack()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    AppErrorDialog(
        title = alertTitle,
        message = errorMessage,
        onDismiss = { errorMessage = null }
    )
}

@Composable
private fun ChannelBindingRow(
    channel: SyncChannelKind,
    identity: PlayerIdentity,
    cnDirectAvailable: Boolean,
    controlsDisabled: Boolean,
    onBind: () -> Unit,
    onRequestUnbind: () -> Unit
) {
    val status = identity.bindingStatus(channel, cnDirectAvailable)
    val subtitle = when (status) {
        ChannelBindingStatus.UNAVAILABLE -> "此版本暂不可用"
        ChannelBindingStatus.UNBOUND -> "未绑定"
        ChannelBindingStatus.BOUND -> "已绑定"
        ChannelBindingStatus.NEEDS_REAUTH -> "仅读取权限，无法上传"
    }

    AppInsetRow(
        title = channel.displayName,
        subtitle = subtitle,
        trailing = {
            when (status) {
                ChannelBindingStatus.UNAVAILABLE -> Unit
                ChannelBindingStatus.UNBOUND -> {
                    AppInlineButton(
                        title = "绑定",
                        enabled = !controlsDisabled,
                        modifier = Modifier.semantics {
                            contentDescription = "绑定 ${channel.displayName}"
                        },
                        onClick = onBind
                    )
                }

                ChannelBindingStatus.BOUND -> {
                    AppInlineButton(
                        title = "解绑",
                        tint = AppTheme.destructive,
                        enabled = !controlsDisabled,
                        modifier = Modifier.semantics {
                            contentDescription = "解绑 ${channel.displayName}"
                        },
                        onClick = onRequestUnbind
                    )
                }

                ChannelBindingStatus.NEEDS_REAUTH -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                    ) {
                        AppInlineButton(
                            title = "重新授权",
                            tint = AppTheme.warning,
                            enabled = !controlsDisabled,
                            modifier = Modifier.semantics {
                                contentDescription = "重新授权 ${channel.displayName}"
                            },
                            onClick = onBind
                        )
                        AppInlineButton(
                            title = "解绑",
                            tint = AppTheme.destructive,
                            enabled = !controlsDisabled,
                            modifier = Modifier.semantics {
                                contentDescription = "解绑 ${channel.displayName}"
                            },
                            onClick = onRequestUnbind
                        )
                    }
                }
            }
        }
    )
}
