package dev.sorrowrain.maimaidata.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.components.AppConfirmDialog
import dev.sorrowrain.maimaidata.components.AppErrorDialog
import dev.sorrowrain.maimaidata.components.AppInlineButton
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppInsetToggleRow
import dev.sorrowrain.maimaidata.components.AppPrimaryButton
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppTextField
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.ScoreProfileRowLabel
import dev.sorrowrain.maimaidata.components.StatusToastHost
import dev.sorrowrain.maimaidata.components.appInsetRowHighlight
import dev.sorrowrain.maimaidata.components.rememberToastState
import dev.sorrowrain.maimaidata.models.ScoreProfile
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.services.MaimaiAPIException
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AccountManagementScreen(
    onBack: () -> Unit
) {
    val store = LocalDataStore.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val toastState = rememberToastState()

    var showAddProfileSheet by remember { mutableStateOf(false) }
    var pendingDeleteProfile by remember { mutableStateOf<ScoreProfile?>(null) }
    var errorDialogTitle by remember { mutableStateOf("同步失败") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "成绩档案",
                    onBack = onBack
                )
            },
            containerColor = Color.Transparent
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
                        .imePadding()
                        .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
                ) {
                    // 1. Score Profiles List Card
                    AppSectionCard(
                        title = "成绩档案",
                        trailing = if (store.activeProfile != null) {
                            {
                                AppInlineButton(
                                    title = "同步当前",
                                    icon = Icons.Rounded.Refresh,
                                    isLoading = store.isSyncingRecords,
                                    onClick = {
                                        scope.launch {
                                            try {
                                                store.syncActiveProfileRecords()
                                                toastState.show("成绩已同步")
                                            } catch (ce: CancellationException) {
                                                throw ce
                                            } catch (err: MaimaiAPIException) {
                                                errorDialogTitle = "同步失败"
                                                errorMessage = err.recoveryMessage
                                            } catch (err: Throwable) {
                                                errorDialogTitle = "同步失败"
                                                errorMessage = "同步失败：${err.message ?: "网络请求异常"}"
                                            }
                                        }
                                    }
                                )
                            }
                        } else {
                            null
                        }
                    ) {
                        if (store.profiles.isEmpty()) {
                            AppInsetGroup(padding = AppTheme.cardPadding) {
                                Text(
                                    text = "暂无成绩档案，点击下方按钮选择落雪或水鱼添加",
                                    style = AppTheme.AppTypography.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            AppInsetGroup {
                                store.profiles.forEachIndexed { idx, profile ->
                                    if (idx > 0) {
                                        AppInsetDivider()
                                    }
                                    val isCurrent = profile.id == store.activeProfileId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .appInsetRowHighlight(isCurrent)
                                            .padding(
                                                start = AppTheme.Space.m,
                                                end = AppTheme.Space.xs
                                            ),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .defaultMinSize(minHeight = AppTheme.rowMinHeight)
                                                .clickable(role = Role.Button) {
                                                    if (!isCurrent) {
                                                        store.switchProfile(profile.id)
                                                        toastState.show("已切换至 ${profile.displayTitle}")
                                                    }
                                                },
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            ScoreProfileRowLabel(
                                                profile = profile,
                                                isSelected = isCurrent
                                            )
                                        }

                                        IconButton(
                                            onClick = { pendingDeleteProfile = profile },
                                            enabled = !store.isSyncingRecords,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = "移除成绩档案 ${profile.displayTitle}",
                                                tint = AppTheme.destructiveText,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Add Score Profile Primary Action
                    AppPrimaryButton(
                        title = "添加成绩档案",
                        icon = Icons.Rounded.AddCircle,
                        enabled = !store.isSyncingRecords,
                        onClick = { showAddProfileSheet = true }
                    )

                    // 3. B50 Image Player Name Section
                    AppSectionCard(title = "B50 图片玩家名称") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
                        ) {
                            AppInsetGroup {
                                AppInsetToggleRow(
                                    title = "使用查分器返回昵称",
                                    checked = store.useProberNickname,
                                    onCheckedChange = {
                                        focusManager.clearFocus()
                                        store.useProberNickname = it
                                    }
                                )
                            }

                            AnimatedVisibility(
                                visible = !store.useProberNickname,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                AppTextField(
                                    value = store.customNickname,
                                    onValueChange = { store.customNickname = it },
                                    placeholder = "输入自定义显示的玩家昵称",
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = { focusManager.clearFocus() }
                                    )
                                )
                            }
                        }
                    }
                }

                StatusToastHost(
                    toastState = toastState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    if (showAddProfileSheet) {
        LoginSheet(
            onDismiss = { showAddProfileSheet = false },
            onProfileAdded = { profile ->
                toastState.show("已添加 ${profile.displayTitle}")
            },
            onWarningMessage = { msg ->
                errorDialogTitle = "档案已添加"
                errorMessage = msg
            }
        )
    }

    val deleteTarget = pendingDeleteProfile
    if (deleteTarget != null) {
        val isDeletingActive = deleteTarget.id == store.activeProfileId
        AppConfirmDialog(
            title = if (isDeletingActive) "移除当前成绩档案" else "移除成绩档案",
            message = if (isDeletingActive) {
                "确定要移除当前使用的成绩档案「${deleteTarget.displayTitle}」及其本地缓存成绩吗？"
            } else {
                "确定要移除成绩档案「${deleteTarget.displayTitle}」及其本地缓存成绩吗？"
            },
            confirmText = "移除",
            isDestructive = true,
            onConfirm = {
                store.removeProfile(deleteTarget.id)
                pendingDeleteProfile = null
                toastState.show("已移除 ${deleteTarget.displayTitle}")
            },
            onDismiss = { pendingDeleteProfile = null }
        )
    }

    AppErrorDialog(
        title = errorDialogTitle,
        message = errorMessage,
        onDismiss = { errorMessage = null }
    )
}
