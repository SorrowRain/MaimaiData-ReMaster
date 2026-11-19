package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.CloudDownload
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.sorrowrain.maimaidata.bridges.CustomTabOAuthLauncher
import dev.sorrowrain.maimaidata.components.AppErrorDialog
import dev.sorrowrain.maimaidata.components.AppInlineButton
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppPrimaryButton
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppSecureField
import dev.sorrowrain.maimaidata.components.AppSegmentedControl
import dev.sorrowrain.maimaidata.components.AppTextField
import dev.sorrowrain.maimaidata.models.ScoreProfile
import dev.sorrowrain.maimaidata.models.ScoreProvider
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.services.MaimaiAPIException
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSheet(
    onDismiss: () -> Unit,
    onProfileAdded: (ScoreProfile) -> Unit = {},
    onWarningMessage: ((String) -> Unit)? = null
) {
    val store = LocalDataStore.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var selectedProvider by rememberSaveable { mutableStateOf(ScoreProvider.LXNS) }
    var lxnsUsePersonalToken by rememberSaveable { mutableStateOf(false) }
    var dfUseImportToken by rememberSaveable { mutableStateOf(false) }

    var dfUsername by rememberSaveable { mutableStateOf("") }
    var dfPassword by rememberSaveable { mutableStateOf("") }
    var dfImportToken by rememberSaveable { mutableStateOf("") }
    var lxnsPersonalToken by rememberSaveable { mutableStateOf("") }
    var remarkName by rememberSaveable { mutableStateOf("") }

    var isSubmitting by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !(isSubmitting && it == SheetValue.Hidden) }
    )
    var errorTitle by remember { mutableStateOf("授权或同步提示") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var shouldDismissAfterAlert by remember { mutableStateOf(false) }

    fun handleRecordSyncFailureAfterLogin(err: MaimaiAPIException.RecordSyncFailedAfterLogin) {
        store.activeProfile?.let(onProfileAdded)
        if (onWarningMessage != null) {
            onDismiss()
            onWarningMessage(err.recoveryMessage)
        } else {
            shouldDismissAfterAlert = true
            errorTitle = "档案已添加"
            errorMessage = err.recoveryMessage
        }
    }

    LaunchedEffect(store) {
        store.pendingOAuthCallbacks.collect { callbackUri ->
            isSubmitting = true
            try {
                val profile = store.completeLXNSOAuth(callbackUri)
                onProfileAdded(profile)
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

    val requestClose = {
        if (!isSubmitting) {
            store.cancelPendingLXNSOAuth()
            onDismiss()
        }
    }

    fun submitLXNSPersonalToken() {
        if (isSubmitting || lxnsPersonalToken.isBlank()) return
        focusManager.clearFocus()
        scope.launch {
            isSubmitting = true
            try {
                val profile = store.addLXNSPersonalTokenProfile(lxnsPersonalToken, remarkName)
                onProfileAdded(profile)
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
                val profile = store.addDivingFishPasswordProfile(dfUsername, dfPassword)
                onProfileAdded(profile)
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
                val profile = store.addDivingFishImportTokenProfile(dfImportToken)
                onProfileAdded(profile)
                onDismiss()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: MaimaiAPIException) {
                errorTitle = "授权或同步提示"
                errorMessage = err.recoveryMessage
            } catch (err: Throwable) {
                errorTitle = "授权或同步提示"
                errorMessage = "验证失败：${err.message ?: "网络异常"}\n请检查导入 Token 是否正确。"
            } finally {
                isSubmitting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = requestClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppTheme.Radius.card, topEnd = AppTheme.Radius.card),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = AppTheme.pagePadding)
                .padding(bottom = AppTheme.Space.xl),
            verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "添加成绩档案",
                    style = AppTheme.AppTypography.cardTitle,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AppInlineButton(
                    title = "关闭",
                    isNeutral = true,
                    enabled = !isSubmitting,
                    onClick = requestClose
                )
            }

            // 1. Score Provider Selector
            AppSectionCard(
                title = "成绩来源",
                subtitle = "成绩档案仅用于拉取并本地保存成绩单，与外面的游戏区服算分规则互不绑定"
            ) {
                val providers = ScoreProvider.entries
                AppSegmentedControl(
                    options = providers.map { it.displayName },
                    selectedIndex = providers.indexOf(selectedProvider).coerceAtLeast(0),
                    onSelect = { index -> if (!isSubmitting) selectedProvider = providers[index] },
                    enabled = !isSubmitting && !store.isLoadingLocalData
                )
            }

            // 2. Provider-specific Auth Section
            if (selectedProvider == ScoreProvider.LXNS) {
                AppSectionCard(
                    title = "连接落雪咖啡屋",
                    subtitle = "凭据与令牌仅加密存储在设备本机安全存储"
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                    ) {
                        AppSegmentedControl(
                            options = listOf("OAuth 授权", "个人 API 密钥"),
                            selectedIndex = if (lxnsUsePersonalToken) 1 else 0,
                            onSelect = { index -> if (!isSubmitting) lxnsUsePersonalToken = (index == 1) },
                            enabled = !isSubmitting && !store.isLoadingLocalData
                        )

                        if (!lxnsUsePersonalToken) {
                            AppInsetGroup(padding = AppTheme.Space.m) {
                                Text(
                                    text = "点击下方按钮在系统安全网页中登录落雪账号并确认只读授权，授权完成后将自动跳回应用并拉取全量成绩单。",
                                    style = AppTheme.AppTypography.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AppPrimaryButton(
                                title = if (isSubmitting) "正在授权并同步成绩…" else "前往落雪咖啡屋授权",
                                icon = Icons.Rounded.VerifiedUser,
                                isLoading = isSubmitting,
                                enabled = !isSubmitting && !store.isLoadingLocalData,
                                onClick = {
                                    focusManager.clearFocus()
                                    try {
                                        val authorizeUrl = store.beginLXNSOAuth()
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
                        } else {
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
                                title = if (isSubmitting) "正在验证并同步成绩…" else "验证并保存档案",
                                icon = Icons.Rounded.CloudDownload,
                                isLoading = isSubmitting,
                                enabled = !isSubmitting &&
                                    !store.isLoadingLocalData &&
                                    lxnsPersonalToken.isNotBlank(),
                                onClick = { submitLXNSPersonalToken() }
                            )
                        }
                    }
                }
            } else {
                AppSectionCard(
                    title = "连接水鱼查分器",
                    subtitle = "凭据仅加密存储在设备本机安全存储"
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                    ) {
                        AppSegmentedControl(
                            options = listOf("账号密码", "成绩导入 Token"),
                            selectedIndex = if (dfUseImportToken) 1 else 0,
                            onSelect = { index -> if (!isSubmitting) dfUseImportToken = (index == 1) },
                            enabled = !isSubmitting && !store.isLoadingLocalData
                        )

                        if (!dfUseImportToken) {
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
                                title = if (isSubmitting) "正在登录并同步成绩…" else "登录并保存档案",
                                icon = Icons.AutoMirrored.Rounded.Login,
                                isLoading = isSubmitting,
                                enabled = !isSubmitting &&
                                    !store.isLoadingLocalData &&
                                    dfUsername.isNotBlank() &&
                                    dfPassword.isNotEmpty(),
                                onClick = { submitDivingFishPassword() }
                            )
                        } else {
                            AppSecureField(
                                value = dfImportToken,
                                onValueChange = { dfImportToken = it },
                                label = "成绩导入 Token",
                                placeholder = "粘贴水鱼「编辑个人资料」生成的成绩导入 Token",
                                keyboardActions = KeyboardActions(onDone = { submitDivingFishImportToken() })
                            )

                            AppInsetGroup(padding = AppTheme.Space.m) {
                                Text(
                                    text = "无需输入水鱼账号密码，直接通过 Import-Token 拉取完整成绩单与查分器昵称。",
                                    style = AppTheme.AppTypography.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AppPrimaryButton(
                                title = if (isSubmitting) "正在验证并同步成绩…" else "验证并保存档案",
                                icon = Icons.Rounded.CloudDownload,
                                isLoading = isSubmitting,
                                enabled = !isSubmitting &&
                                    !store.isLoadingLocalData &&
                                    dfImportToken.isNotBlank(),
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
