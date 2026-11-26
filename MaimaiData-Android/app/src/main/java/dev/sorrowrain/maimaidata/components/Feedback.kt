package dev.sorrowrain.maimaidata.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Race-free toast state controller: showing a new toast cancels any previous timer [Job]
 * before starting the new delay so rapid taps never truncate the latest toast.
 */
@Stable
class ToastState(private val scope: CoroutineScope) {
    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)
    private var timerJob: Job? = null

    fun show(text: String, isError: Boolean = false, durationMs: Long = 1800L) {
        timerJob?.cancel()
        this.isError = isError
        message = text
        timerJob = scope.launch {
            delay(durationMs)
            message = null
        }
    }
}

@Composable
fun rememberToastState(): ToastState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ToastState(scope) }
}

/**
 * Elevated pill toast host rendered at the bottom of a screen or Box container.
 */
@Composable
fun StatusToastHost(
    toastState: ToastState,
    modifier: Modifier = Modifier
) {
    var lastMessage by remember { mutableStateOf("") }
    var lastIsError by remember { mutableStateOf(false) }
    val activeMessage = toastState.message
    val activeIsError = toastState.isError

    SideEffect {
        if (activeMessage != null) {
            lastMessage = activeMessage
            lastIsError = activeIsError
        }
    }

    val displayedText = activeMessage ?: lastMessage
    val displayedIsError = if (activeMessage != null) activeIsError else lastIsError

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.xl),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = activeMessage != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppTheme.Space.l, vertical = AppTheme.itemSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                ) {
                    Icon(
                        imageVector = if (displayedIsError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = if (displayedIsError) AppTheme.warningText else AppTheme.accentText,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = displayedText,
                        style = AppTheme.AppTypography.inlineAction,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Inline catalog/stats sync progress or notice banner with optional retry and dismiss actions.
 */
@Composable
fun SyncStatusBanner(
    message: String,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    AppCard(
        modifier = modifier,
        padding = AppTheme.compactCardPadding,
        tint = if (isLoading) AppTheme.accent else AppTheme.warning
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.accentText
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = AppTheme.warningText,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = message,
                    style = if (isLoading) AppTheme.AppTypography.tag else AppTheme.AppTypography.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            if (!isLoading && (onRetry != null || onDismiss != null)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
                ) {
                    if (onRetry != null) {
                        AppInlineButton(
                            title = "重试",
                            icon = Icons.Rounded.Refresh,
                            onClick = onRetry
                        )
                    }
                    if (onDismiss != null) {
                        AppInlineButton(
                            title = "关闭",
                            isNeutral = true,
                            onClick = onDismiss
                        )
                    }
                }
            }
        }
    }
}

/**
 * Standard Material 3 error/notice dialog displayed whenever [message] is non-null.
 */
@Composable
fun AppErrorDialog(
    title: String = "提示",
    message: String?,
    onDismiss: () -> Unit
) {
    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = title,
                    style = AppTheme.AppTypography.cardTitle,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = message,
                    style = AppTheme.AppTypography.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "确定",
                        style = AppTheme.AppTypography.subheadlineBold,
                        color = AppTheme.accentText
                    )
                }
            },
            shape = RoundedCornerShape(AppTheme.Radius.card),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

/**
 * Standard Material 3 confirmation dialog with consistent card corner radius and typography.
 */
@Composable
fun AppConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String = "取消",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    extraActionText: String? = null,
    onExtraAction: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = AppTheme.AppTypography.cardTitle,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                text = message,
                style = AppTheme.AppTypography.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    style = AppTheme.AppTypography.subheadlineBold,
                    color = if (isDestructive) AppTheme.destructiveText else AppTheme.accentText
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)) {
                if (extraActionText != null && onExtraAction != null) {
                    TextButton(onClick = onExtraAction) {
                        Text(
                            text = extraActionText,
                            style = AppTheme.AppTypography.rowTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = dismissText,
                        style = AppTheme.AppTypography.rowTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        shape = RoundedCornerShape(AppTheme.Radius.card),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/**
 * Standard centered loading placeholder (counterpart to iOS `ProgressView` state).
 */
@Composable
fun AppLoadingState(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.Space.l, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.5.dp,
            color = AppTheme.accentText
        )
        Text(
            text = message,
            style = AppTheme.AppTypography.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Standard centered empty/completed state placeholder (counterpart to iOS `ContentUnavailableView`).
 */
@Composable
fun AppEmptyState(
    icon: ImageVector,
    title: String,
    description: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.Space.l, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(38.dp)
        )
        Text(
            text = title,
            style = AppTheme.AppTypography.cardTitle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (!description.isNullOrEmpty()) {
            Text(
                text = description,
                style = AppTheme.AppTypography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (action != null) {
            Box(modifier = Modifier.padding(top = AppTheme.Space.xs)) {
                action()
            }
        }
    }
}

/**
 * Clean Material 3 centered top app bar with translucent surface container color and optional back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                style = AppTheme.AppTypography.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回"
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
    )
}

/**
 * Standard ModalBottomSheet centered header with left action (back or close) and balanced spacer.
 */
@Composable
fun AppSheetHeader(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack, enabled = enabled) {
                    Text(
                        text = "返回",
                        style = AppTheme.AppTypography.rowTitle,
                        color = AppTheme.accentText
                    )
                }
            } else {
                TextButton(onClick = onClose, enabled = enabled) {
                    Text(
                        text = "关闭",
                        style = AppTheme.AppTypography.rowTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            text = title,
            style = AppTheme.AppTypography.cardTitle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(modifier = Modifier.weight(1f))
    }
}
