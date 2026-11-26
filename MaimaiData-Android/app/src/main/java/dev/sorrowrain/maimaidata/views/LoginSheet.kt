package dev.sorrowrain.maimaidata.views

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.sorrowrain.maimaidata.bridges.CustomTabOAuthLauncher
import dev.sorrowrain.maimaidata.components.AppErrorDialog
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppInsetRow
import dev.sorrowrain.maimaidata.components.AppPasteField
import dev.sorrowrain.maimaidata.components.AppPrimaryButton
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppSecureField
import dev.sorrowrain.maimaidata.components.AppSheetHeader
import dev.sorrowrain.maimaidata.components.AppTextField
import dev.sorrowrain.maimaidata.models.PlayerIdentity
import dev.sorrowrain.maimaidata.models.SyncChannelKind
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.services.MaimaiAPIException
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddIdentitySheet(
    onDismiss: () -> Unit,
    onCompleted: (String) -> Unit = {},
    onWarningMessage: ((String) -> Unit)? = null
) {
    val store = LocalDataStore.current
    var selectedChannel by rememberSaveable { mutableStateOf<SyncChannelKind?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !(isSubmitting && it == SheetValue.Hidden) }
    )

    val availableChannels = remember(store.isCNDirectAvailable) {
        SyncChannelKind.entries.filter {
            it != SyncChannelKind.SELF_HOSTED || store.isCNDirectAvailable
        }
    }

    val requestClose = {
        if (!isSubmitting) {
            store.cancelPendingLXNSOAuth()
            onDismiss()
        }
    }

    val forceClose = {
        store.cancelPendingLXNSOAuth()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = requestClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppTheme.Radius.card, topEnd = AppTheme.Radius.card),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        val activeChannel = selectedChannel
        if (activeChannel == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.s)
                    .padding(bottom = AppTheme.Space.l),
                verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
            ) {
                AppSheetHeader(
                    title = "添加档案",
                    onClose = requestClose
                )

                AppSectionCard(
                    title = "选择成绩来源",
                    subtitle = "添加后还可以在档案详情里继续绑定其他账号"
                ) {
                    AppInsetGroup {
                        availableChannels.forEachIndexed { idx, channel ->
                            if (idx > 0) {
                                AppInsetDivider()
                            }
                            AppInsetRow(
                                title = channel.displayName,
                                subtitle = channel.sourceSubtitle(),
                                icon = channel.sourceIcon(),
                                iconTint = channel.sourceTint(),
                                showsChevron = true,
                                onClick = { selectedChannel = channel }
                            )
                        }
                    }
                }
            }
        } else {
            ChannelBindFormContent(
                channel = activeChannel,
                targetIdentityId = null,
                onBack = { selectedChannel = null },
                onRequestClose = requestClose,
                onDismiss = forceClose,
                onSubmittingChange = { isSubmitting = it },
                onCompleted = onCompleted,
                onWarningMessage = onWarningMessage
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelBindSheet(
    channel: SyncChannelKind,
    targetIdentityId: String?,
    onDismiss: () -> Unit,
    onCompleted: (String) -> Unit = {},
    onWarningMessage: ((String) -> Unit)? = null
) {
    val store = LocalDataStore.current
    var isSubmitting by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !(isSubmitting && it == SheetValue.Hidden) }
    )

    val requestClose = {
        if (!isSubmitting) {
            store.cancelPendingLXNSOAuth()
            onDismiss()
        }
    }

    val forceClose = {
        store.cancelPendingLXNSOAuth()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = requestClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppTheme.Radius.card, topEnd = AppTheme.Radius.card),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        ChannelBindFormContent(
            channel = channel,
            targetIdentityId = targetIdentityId,
            onBack = null,
            onRequestClose = requestClose,
            onDismiss = forceClose,
            onSubmittingChange = { isSubmitting = it },
            onCompleted = onCompleted,
            onWarningMessage = onWarningMessage
        )
    }
}

@Composable
private fun ChannelBindFormContent(
    channel: SyncChannelKind,
    targetIdentityId: String?,
    onBack: (() -> Unit)?,
    onRequestClose: () -> Unit,
    onDismiss: () -> Unit,
    onSubmittingChange: (Boolean) -> Unit,
    onCompleted: (String) -> Unit,
    onWarningMessage: ((String) -> Unit)?
) {
    val store = LocalDataStore.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var secondaryStep by rememberSaveable(channel) { mutableStateOf(false) }

    var remarkName by rememberSaveable { mutableStateOf("") }
    var dfUsername by rememberSaveable { mutableStateOf("") }

    // Sensitive credentials/tokens/QR contents intentionally kept in volatile `remember`
    // so Android SavedState never serializes secrets into transaction bundles.
    var qrContent by remember { mutableStateOf("") }
    var dfPassword by remember { mutableStateOf("") }
    var dfImportToken by remember { mutableStateOf("") }
    var lxnsPersonalToken by remember { mutableStateOf("") }

    var isSubmitting by remember { mutableStateOf(false) }
    var errorTitle by remember { mutableStateOf("授权或同步提示") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var shouldDismissAfterAlert by remember { mutableStateOf(false) }

    BackHandler(enabled = secondaryStep && !isSubmitting) {
        secondaryStep = false
    }

    LaunchedEffect(isSubmitting) {
        onSubmittingChange(isSubmitting)
    }

    fun handleRecordSyncFailureAfterLogin(err: MaimaiAPIException.RecordSyncFailedAfterLogin) {
        if (onWarningMessage != null) {
            onDismiss()
            onWarningMessage(err.recoveryMessage)
        } else {
            shouldDismissAfterAlert = true
            errorTitle = "档案已添加"
            errorMessage = err.recoveryMessage
        }
    }

    LaunchedEffect(store, channel) {
        if (channel != SyncChannelKind.LXNS) return@LaunchedEffect
        store.pendingOAuthCallbacks.collect { callbackUri ->
            isSubmitting = true
            try {
                store.completeLXNSOAuth(callbackUri)
                onCompleted("已绑定 ${SyncChannelKind.LXNS.displayName}")
                onDismiss()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: MaimaiAPIException.RecordSyncFailedAfterLogin) {
                handleRecordSyncFailureAfterLogin(err)
            } catch (err: MaimaiAPIException) {
                errorTitle = "授权或同步提示"
                errorMessage = err.recoveryMessage
            } catch (err: Throwable) {
                errorTitle = "授权或同步提示"
                errorMessage = "落雪授权失败：${err.message ?: "请检查网络连接后重试"}"
            } finally {
                isSubmitting = false
            }
        }
    }

    fun submitCNDirectBind() {
        val trimmedQR = qrContent.trim()
        if (isSubmitting || trimmedQR.isEmpty()) return
        focusManager.clearFocus()
        scope.launch {
            isSubmitting = true
            try {
                store.bindSelfHosted(
                    qrContent = trimmedQR,
                    initialFullSync = true,
                    targetIdentityId = targetIdentityId
                )
                onCompleted("已绑定 ${SyncChannelKind.SELF_HOSTED.displayName}")
                onDismiss()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: MaimaiAPIException) {
                errorTitle = "绑定或同步提示"
                errorMessage = err.recoveryMessage
            } catch (err: Throwable) {
                errorTitle = "绑定或同步提示"
                errorMessage = err.message ?: "国服直连绑定失败"
            } finally {
                isSubmitting = false
            }
        }
    }

    fun submitLXNSPersonalToken() {
        if (isSubmitting || lxnsPersonalToken.isBlank()) return
        focusManager.clearFocus()
        scope.launch {
            isSubmitting = true
            try {
                store.bindLXNSPersonalToken(
                    rawToken = lxnsPersonalToken,
                    remarkName = remarkName,
                    targetIdentityId = targetIdentityId
                )
                onCompleted("已绑定 ${SyncChannelKind.LXNS.displayName}")
                onDismiss()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: MaimaiAPIException) {
                errorTitle = "授权或同步提示"
                errorMessage = err.recoveryMessage
            } catch (err: Throwable) {
                errorTitle = "授权或同步提示"
                errorMessage = "验证失败：${err.message ?: "网络异常"}\n请检查网络连接或个人密钥是否正确。"
            } finally {
                isSubmitting = false
            }
        }
    }

    fun submitDivingFishPassword() {
        if (isSubmitting || dfUsername.isBlank() || dfPassword.isEmpty()) return
        focusManager.clearFocus()
        scope.launch {
            isSubmitting = true
            try {
                store.bindDivingFishPassword(
                    username = dfUsername,
                    password = dfPassword,
                    targetIdentityId = targetIdentityId
                )
                onCompleted("已绑定 ${SyncChannelKind.DIVING_FISH.displayName}")
                onDismiss()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: MaimaiAPIException.RecordSyncFailedAfterLogin) {
                handleRecordSyncFailureAfterLogin(err)
            } catch (err: MaimaiAPIException) {
                errorTitle = "授权或同步提示"
                errorMessage = err.recoveryMessage
            } catch (err: Throwable) {
                errorTitle = "授权或同步提示"
                errorMessage = "登录失败：${err.message ?: "网络异常"}\n请检查网络连接后重试。"
            } finally {
                isSubmitting = false
            }
        }
    }

    fun submitDivingFishImportToken() {
        if (isSubmitting || dfImportToken.isBlank()) return
        focusManager.clearFocus()
        scope.launch {
            isSubmitting = true
            try {
                store.bindDivingFishImportToken(
                    rawToken = dfImportToken,
                    targetIdentityId = targetIdentityId
                )
                onCompleted("已绑定 ${SyncChannelKind.DIVING_FISH.displayName}")
                onDismiss()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: MaimaiAPIException) {
                errorTitle = "授权或同步提示"
                errorMessage = err.recoveryMessage
            } catch (err: Throwable) {
                errorTitle = "授权或同步提示"
                errorMessage = "验证失败：${err.message ?: "网络异常"}\n请检查成绩导入密钥是否正确。"
            } finally {
                isSubmitting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.s)
            .padding(bottom = AppTheme.Space.l),
        verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
    ) {
        AppSheetHeader(
            title = when {
                !secondaryStep -> "绑定${channel.displayName}"
                channel == SyncChannelKind.LXNS -> "使用个人 API 密钥"
                else -> "使用成绩导入密钥"
            },
            onClose = onRequestClose,
            onBack = if (secondaryStep) ({ secondaryStep = false }) else onBack,
            enabled = !isSubmitting
        )

        when (channel) {
            SyncChannelKind.SELF_HOSTED -> {
                AppSectionCard(
                    title = "粘贴玩家二维码",
                    subtitle = "获取玩家二维码，长按识别后复制文本"
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                    ) {
                        QRPasteInputRow(
                            text = qrContent,
                            onValueChange = { qrContent = it },
                            onDone = { submitCNDirectBind() }
                        )

                        AppPrimaryButton(
                            title = if (isSubmitting) "正在绑定并同步…" else "绑定并同步成绩",
                            icon = Icons.Rounded.QrCode2,
                            isLoading = isSubmitting,
                            enabled = !isSubmitting && !store.isLoadingLocalData && qrContent.isNotBlank(),
                            onClick = { submitCNDirectBind() }
                        )
                    }
                }
            }

            SyncChannelKind.LXNS -> {
                if (!secondaryStep) {
                    AppSectionCard(
                        title = "连接落雪咖啡屋",
                        subtitle = "凭据仅加密保存在本机"
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                        ) {
                            AppInsetGroup(padding = AppTheme.Space.m) {
                                Text(
                                    text = "将跳转到落雪网页登录，并授权读取和上传成绩。",
                                    style = AppTheme.AppTypography.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AppPrimaryButton(
                                title = if (isSubmitting) "正在授权并同步…" else "前往落雪授权",
                                icon = Icons.Rounded.VerifiedUser,
                                isLoading = isSubmitting,
                                enabled = !isSubmitting && !store.isLoadingLocalData,
                                onClick = {
                                    focusManager.clearFocus()
                                    try {
                                        val authorizeUrl = store.beginLXNSOAuth(targetIdentityId)
                                        CustomTabOAuthLauncher.launch(context, authorizeUrl)
                                    } catch (err: MaimaiAPIException) {
                                        errorTitle = "授权或同步提示"
                                        errorMessage = err.recoveryMessage
                                    } catch (err: Throwable) {
                                        errorTitle = "授权或同步提示"
                                        errorMessage = "无法打开授权页面：${err.message ?: "请检查系统浏览器设置"}"
                                    }
                                }
                            )

                            AppInsetDivider()

                            AppInsetGroup {
                                AppInsetRow(
                                    title = "使用个人 API 密钥",
                                    showsChevron = true,
                                    onClick = { if (!isSubmitting) secondaryStep = true }
                                )
                            }
                        }
                    }
                } else {
                    AppSectionCard(
                        title = "连接落雪咖啡屋",
                        subtitle = "凭据仅加密保存在本机"
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                        ) {
                            AppSecureField(
                                value = lxnsPersonalToken,
                                onValueChange = { lxnsPersonalToken = it },
                                label = "个人 API 密钥",
                                placeholder = "粘贴落雪「账号详情」生成的个人 API 密钥",
                                keyboardActions = KeyboardActions(onDone = { submitLXNSPersonalToken() })
                            )

                            AppTextField(
                                value = remarkName,
                                onValueChange = { remarkName = it },
                                label = "备注名称（选填）",
                                placeholder = "未同步过游戏数据时的备用名称",
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submitLXNSPersonalToken() })
                            )

                            AppPrimaryButton(
                                title = if (isSubmitting) "正在验证并同步…" else "验证并绑定",
                                icon = Icons.Rounded.CloudDownload,
                                isLoading = isSubmitting,
                                enabled = !isSubmitting && !store.isLoadingLocalData && lxnsPersonalToken.isNotBlank(),
                                onClick = { submitLXNSPersonalToken() }
                            )
                        }
                    }
                }
            }

            SyncChannelKind.DIVING_FISH -> {
                if (!secondaryStep) {
                    AppSectionCard(
                        title = "连接水鱼查分器",
                        subtitle = "凭据仅加密保存在本机"
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                        ) {
                            AppTextField(
                                value = dfUsername,
                                onValueChange = { dfUsername = it },
                                label = "用户名",
                                placeholder = "输入水鱼用户名",
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Next
                                )
                            )

                            AppSecureField(
                                value = dfPassword,
                                onValueChange = { dfPassword = it },
                                label = "密码",
                                placeholder = "输入水鱼密码",
                                keyboardActions = KeyboardActions(onDone = { submitDivingFishPassword() })
                            )

                            AppPrimaryButton(
                                title = if (isSubmitting) "正在登录并同步…" else "登录并绑定",
                                icon = Icons.AutoMirrored.Rounded.Login,
                                isLoading = isSubmitting,
                                enabled = !isSubmitting &&
                                    !store.isLoadingLocalData &&
                                    dfUsername.isNotBlank() &&
                                    dfPassword.isNotEmpty(),
                                onClick = { submitDivingFishPassword() }
                            )

                            AppInsetDivider()

                            AppInsetGroup {
                                AppInsetRow(
                                    title = "使用成绩导入密钥",
                                    showsChevron = true,
                                    onClick = { if (!isSubmitting) secondaryStep = true }
                                )
                            }
                        }
                    }
                } else {
                    AppSectionCard(
                        title = "连接水鱼查分器",
                        subtitle = "凭据仅加密保存在本机"
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                        ) {
                            AppSecureField(
                                value = dfImportToken,
                                onValueChange = { dfImportToken = it },
                                label = "成绩导入密钥",
                                placeholder = "粘贴水鱼「编辑个人资料」生成的成绩导入密钥",
                                keyboardActions = KeyboardActions(onDone = { submitDivingFishImportToken() })
                            )

                            AppInsetGroup(padding = AppTheme.Space.m) {
                                Text(
                                    text = "成绩导入密钥可同时用于获取和上传成绩。",
                                    style = AppTheme.AppTypography.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AppPrimaryButton(
                                title = if (isSubmitting) "正在验证并同步…" else "验证并绑定",
                                icon = Icons.Rounded.CloudDownload,
                                isLoading = isSubmitting,
                                enabled = !isSubmitting && !store.isLoadingLocalData && dfImportToken.isNotBlank(),
                                onClick = { submitDivingFishImportToken() }
                            )
                        }
                    }
                }
            }
        }
    }

    AppErrorDialog(
        title = errorTitle,
        message = errorMessage,
        onDismiss = {
            errorMessage = null
            if (shouldDismissAfterAlert) {
                shouldDismissAfterAlert = false
                onDismiss()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullSyncSheet(
    identity: PlayerIdentity,
    onDismiss: () -> Unit,
    onCompleted: (String) -> Unit = {}
) {
    val store = LocalDataStore.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var qrContent by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !(isSubmitting && it == SheetValue.Hidden) }
    )

    val subtitle = if (store.identities.size > 1) {
        "将覆盖 ${identity.primaryDisplayName} 的本地成绩标记；获取玩家二维码，长按识别后复制文本"
    } else {
        "获取玩家二维码，长按识别后复制文本"
    }

    fun runFullSync() {
        val trimmedQR = qrContent.trim()
        if (isSubmitting || trimmedQR.isEmpty()) return
        focusManager.clearFocus()
        scope.launch {
            isSubmitting = true
            try {
                store.fullSyncSelfHosted(
                    identityId = identity.id,
                    qrContent = trimmedQR,
                    writeback = false
                )
                onCompleted("完整同步完成")
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: MaimaiAPIException) {
                errorMessage = err.recoveryMessage
            } catch (err: Throwable) {
                errorMessage = err.message ?: "完整同步失败"
            } finally {
                isSubmitting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppTheme.Radius.card, topEnd = AppTheme.Radius.card),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.s)
                .padding(bottom = AppTheme.Space.l),
            verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
        ) {
            AppSheetHeader(
                title = "完整同步",
                onClose = { if (!isSubmitting) onDismiss() },
                enabled = !isSubmitting
            )

            AppSectionCard(
                title = "粘贴玩家二维码",
                subtitle = subtitle
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                ) {
                    QRPasteInputRow(
                        text = qrContent,
                        onValueChange = { qrContent = it },
                        onDone = { runFullSync() }
                    )

                    AppPrimaryButton(
                        title = if (isSubmitting) "正在完整同步…" else "开始完整同步",
                        icon = Icons.Rounded.QrCode2,
                        isLoading = isSubmitting,
                        enabled = !isSubmitting && qrContent.isNotBlank(),
                        onClick = { runFullSync() }
                    )
                }
            }
        }
    }

    AppErrorDialog(
        title = "完整同步提示",
        message = errorMessage,
        onDismiss = { errorMessage = null }
    )
}

@Composable
private fun QRPasteInputRow(
    text: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit
) {
    AppPasteField(
        value = text,
        onValueChange = onValueChange,
        placeholder = "粘贴链接或 SGWCMAID 开头的文本",
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
}

private fun SyncChannelKind.sourceIcon(): ImageVector =
    when (this) {
        SyncChannelKind.SELF_HOSTED -> Icons.Rounded.QrCode2
        SyncChannelKind.LXNS -> Icons.Rounded.PersonAdd
        SyncChannelKind.DIVING_FISH -> Icons.Rounded.BarChart
    }

private fun SyncChannelKind.sourceTint(): Color =
    when (this) {
        SyncChannelKind.SELF_HOSTED -> AppTheme.accent
        SyncChannelKind.LXNS -> AppTheme.Palette.ratingPurple
        SyncChannelKind.DIVING_FISH -> AppTheme.Palette.statFC
    }

private fun SyncChannelKind.sourceSubtitle(): String =
    when (this) {
        SyncChannelKind.SELF_HOSTED -> "用微信玩家二维码同步"
        SyncChannelKind.LXNS -> "用落雪账号授权"
        SyncChannelKind.DIVING_FISH -> "用水鱼账号登录"
    }
