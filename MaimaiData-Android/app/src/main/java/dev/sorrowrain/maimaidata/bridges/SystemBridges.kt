package dev.sorrowrain.maimaidata.bridges

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.sorrowrain.maimaidata.components.StatusToastHost
import dev.sorrowrain.maimaidata.components.rememberToastState
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.services.CoverImageManager
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Saves PNG bitmaps into `Pictures/MaimaiData` via [MediaStore.Images.Media] on [Dispatchers.IO].
 */
object MediaStoreSaver {
    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        displayNamePrefix: String = "MaimaiData"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val safePrefix = sanitizeFilePrefix(displayNamePrefix)
            val fileName = "${safePrefix}_${System.currentTimeMillis()}.png"
            val resolver = context.contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MaimaiData")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = resolver.insert(collection, values)
                ?: throw IOException("无法创建系统相册记录")

            try {
                val exportBitmap = ensureSoftwareBitmap(bitmap)
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        if (!exportBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw IOException("PNG 图片写入失败")
                        }
                        out.flush()
                    } ?: throw IOException("无法打开系统相册写入流")
                } finally {
                    if (exportBitmap !== bitmap) {
                        exportBitmap.recycle()
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val completeValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, completeValues, null, null)
                }
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                if (e is CancellationException) throw e
                throw e
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
        }
    }
}

/**
 * Shares a PNG [Bitmap] via [FileProvider] (`cacheDir/shared_images`) and Android's system Sharesheet.
 */
object ShareBridge {
    suspend fun shareBitmapAsync(
        context: Context,
        bitmap: Bitmap,
        fileNamePrefix: String = "MaimaiData_B50"
    ): Result<Unit> = runCatching {
        val uri = withContext(Dispatchers.IO) {
            writeSharedBitmapUri(context, bitmap, fileNamePrefix)
        }
        withContext(Dispatchers.Main) {
            launchShareChooser(context, uri, fileNamePrefix)
        }
    }.onFailure { err ->
        if (err is CancellationException) throw err
    }

    private fun writeSharedBitmapUri(
        context: Context,
        bitmap: Bitmap,
        fileNamePrefix: String
    ): Uri {
        val sharedDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
        val now = System.currentTimeMillis()
        sharedDir.listFiles()?.forEach { existing ->
            if (now - existing.lastModified() > 3_600_000L) {
                runCatching { existing.delete() }
            }
        }

        val safePrefix = sanitizeFilePrefix(fileNamePrefix)
        val outFile = File(sharedDir, "${safePrefix}_$now.png")
        val exportBitmap = ensureSoftwareBitmap(bitmap)
        try {
            FileOutputStream(outFile).use { out ->
                if (!exportBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("生成分享图片失败")
                }
                out.flush()
            }
        } finally {
            if (exportBitmap !== bitmap) {
                exportBitmap.recycle()
            }
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    }

    private fun launchShareChooser(
        context: Context,
        uri: Uri,
        label: String
    ) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(label, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "分享图片").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }
}

/**
 * Centralized external browser link opener used by `MainTabView`, `AboutScreen`, and `AcknowledgmentsScreen`.
 */
object ExternalLinkLauncher {
    fun open(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

/**
 * Launches Chrome Custom Tabs with brand toolbar color, falling back to `ACTION_VIEW`.
 */
object CustomTabOAuthLauncher {
    fun launch(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        return try {
            val colorParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(AppTheme.accent.toArgb())
                .build()
            val customTabsIntent = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(colorParams)
                .setShowTitle(true)
                .build()
            if (context !is Activity) {
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTabsIntent.launchUrl(context, uri)
            true
        } catch (_: Exception) {
            ExternalLinkLauncher.open(context, url)
        }
    }
}

/**
 * Interactive pinch-to-zoom (`1.0x`–`4.5x`) around pinch centroid, bounded pan, and double-tap (`1.0x` / `2.5x`).
 */
@Composable
fun ZoomableImageBox(
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    minScale: Float = 1.0f,
    maxScale: Float = 4.5f,
    doubleTapScale: Float = 2.5f,
    content: @Composable BoxScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var animJob by remember { mutableStateOf<Job?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(resetKey) {
        animJob?.cancel()
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(minScale, maxScale, doubleTapScale) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        animJob?.cancel()
                        val startScale = scale
                        val startX = offsetX
                        val startY = offsetY
                        val targetScale: Float
                        val targetOffset: Offset
                        if (startScale > 1.05f) {
                            targetScale = 1f
                            targetOffset = Offset.Zero
                        } else {
                            targetScale = doubleTapScale.coerceIn(minScale, maxScale)
                            val centerX = containerSize.width / 2f
                            val centerY = containerSize.height / 2f
                            val rawX = (centerX - tapOffset.x) * (targetScale - 1f)
                            val rawY = (centerY - tapOffset.y) * (targetScale - 1f)
                            targetOffset = clampPanOffset(rawX, rawY, targetScale, containerSize)
                        }
                        animJob = scope.launch {
                            animate(
                                initialValue = 0f,
                                targetValue = 1f,
                                animationSpec = tween(220)
                            ) { progress, _ ->
                                scale = startScale + (targetScale - startScale) * progress
                                offsetX = startX + (targetOffset.x - startX) * progress
                                offsetY = startY + (targetOffset.y - startY) * progress
                            }
                        }
                    }
                )
            }
            .pointerInput(minScale, maxScale) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    animJob?.cancel()
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(minScale, maxScale)
                    val scaleRatio = if (oldScale > 0f) newScale / oldScale else 1f
                    val centerX = containerSize.width / 2f
                    val centerY = containerSize.height / 2f
                    val rawX = if (newScale > 1.001f) {
                        (offsetX + centerX - centroid.x) * scaleRatio - (centerX - centroid.x) + pan.x
                    } else {
                        0f
                    }
                    val rawY = if (newScale > 1.001f) {
                        (offsetY + centerY - centroid.y) * scaleRatio - (centerY - centroid.y) + pan.y
                    } else {
                        0f
                    }
                    val clamped = clampPanOffset(rawX, rawY, newScale, containerSize)
                    scale = newScale
                    offsetX = clamped.x
                    offsetY = clamped.y
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

/**
 * Convenience composable rendering a [Bitmap] inside [ZoomableImageBox].
 */
@Composable
fun ZoomableBitmapPreview(
    bitmap: Bitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    ZoomableImageBox(modifier = modifier, resetKey = bitmap) {
        Image(
            bitmap = imageBitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(AppTheme.Space.m)
        )
    }
}

/**
 * Full-screen edge-to-edge black photo preview dialog supporting `1.0x`–`4.5x` pinch-to-zoom,
 * pan, double-tap `1x`/`2.5x` toggle, top bar close button, bottom **保存相册** / **分享** actions,
 * and an internal overlay [StatusToastHost] so save/error toasts remain visible above the dialog.
 */
@Composable
fun PhotoPreviewDialog(
    title: String,
    bitmap: Bitmap?,
    songForCover: SongData? = null,
    onDismiss: () -> Unit,
    onSavedToast: (String) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dialogToast = rememberToastState()
    var fallbackBitmap by remember(songForCover?.id) { mutableStateOf<Bitmap?>(null) }
    var isLoadingCover by remember(songForCover?.id) { mutableStateOf(bitmap == null && songForCover != null) }
    var isSaving by remember { mutableStateOf(false) }
    var isSharing by remember { mutableStateOf(false) }

    val effectiveBitmap = bitmap ?: fallbackBitmap

    fun performSave(target: Bitmap) {
        if (isSaving) return
        isSaving = true
        scope.launch {
            try {
                MediaStoreSaver.saveBitmapToGallery(
                    context = context,
                    bitmap = target,
                    displayNamePrefix = if (songForCover != null) {
                        "MaimaiData_Cover_${songForCover.id}"
                    } else {
                        "MaimaiData_B50"
                    }
                ).fold(
                    onSuccess = {
                        dialogToast.show("已保存到系统相册", durationMs = 2200L)
                        onSavedToast("已保存到系统相册")
                    },
                    onFailure = { err ->
                        val msg = err.message ?: "保存相册失败"
                        dialogToast.show(msg, isError = true, durationMs = 2500L)
                        onError(msg)
                    }
                )
            } finally {
                isSaving = false
            }
        }
    }

    val legacyWritePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            effectiveBitmap?.let(::performSave)
        } else {
            val msg = "请在系统设置中允许存储权限以保存图片"
            dialogToast.show(msg, isError = true, durationMs = 2500L)
            onError(msg)
        }
    }

    LaunchedEffect(bitmap, songForCover?.id) {
        if (bitmap == null && songForCover != null && fallbackBitmap == null) {
            isLoadingCover = true
            try {
                fallbackBitmap = CoverImageManager.loadCoverBitmap(
                    context = context,
                    song = songForCover,
                    targetPx = 0
                )
            } finally {
                isLoadingCover = false
            }
        } else {
            isLoadingCover = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Zoomable center viewport
            when {
                effectiveBitmap != null -> {
                    ZoomableBitmapPreview(
                        bitmap = effectiveBitmap,
                        contentDescription = "$title，可双击或双指缩放",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                isLoadingCover || songForCover == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = if (songForCover != null) "正在加载曲绘…" else "正在加载图片…",
                            style = AppTheme.AppTypography.caption,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BrokenImage,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "曲绘加载失败，请检查网络连接",
                            style = AppTheme.AppTypography.caption,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            // Top bar with subtle gradient scrim + title + close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
            ) {
                Text(
                    text = title,
                    style = AppTheme.AppTypography.cardTitle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Bottom action bar with 保存相册 & 分享 buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.80f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
            ) {
                OutlinedButton(
                    onClick = {
                        val target = effectiveBitmap ?: return@OutlinedButton
                        isSharing = true
                        scope.launch {
                            try {
                                ShareBridge.shareBitmapAsync(
                                    context = context,
                                    bitmap = target,
                                    fileNamePrefix = if (songForCover != null) {
                                        "MaimaiData_Cover_${songForCover.id}"
                                    } else {
                                        "MaimaiData_B50"
                                    }
                                ).onFailure { err ->
                                    val msg = err.message ?: "分享失败"
                                    dialogToast.show(msg, isError = true, durationMs = 2500L)
                                    onError(msg)
                                }
                            } finally {
                                isSharing = false
                            }
                        }
                    },
                    enabled = effectiveBitmap != null && !isSharing,
                    shape = RoundedCornerShape(AppTheme.Radius.button),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.05f),
                        disabledContentColor = Color.White.copy(alpha = 0.40f)
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                    contentPadding = PaddingValues(horizontal = AppTheme.Space.l, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "分享",
                            style = AppTheme.AppTypography.songTitle
                        )
                    }
                }

                Button(
                    onClick = {
                        val target = effectiveBitmap ?: return@Button
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            performSave(target)
                        }
                    },
                    enabled = effectiveBitmap != null && !isSaving,
                    shape = RoundedCornerShape(AppTheme.Radius.button),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.accent,
                        contentColor = Color.White,
                        disabledContainerColor = AppTheme.accent.copy(alpha = 0.45f),
                        disabledContentColor = Color.White.copy(alpha = 0.70f)
                    ),
                    contentPadding = PaddingValues(horizontal = AppTheme.Space.l, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "保存相册",
                            style = AppTheme.AppTypography.songTitle
                        )
                    }
                }
            }

            // Internal overlay toast above the bottom action bar so feedback is visible inside the Dialog window
            StatusToastHost(
                toastState = dialogToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 72.dp)
            )
        }
    }
}

private fun clampPanOffset(
    rawX: Float,
    rawY: Float,
    scale: Float,
    size: IntSize
): Offset {
    if (scale <= 1.001f || size.width <= 0 || size.height <= 0) {
        return Offset.Zero
    }
    val maxX = (size.width * (scale - 1f)) / 2f
    val maxY = (size.height * (scale - 1f)) / 2f
    return Offset(
        x = rawX.coerceIn(-maxX, maxX),
        y = rawY.coerceIn(-maxY, maxY)
    )
}

private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
    return if (bitmap.config == Bitmap.Config.HARDWARE) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
    } else {
        bitmap
    }
}

private fun sanitizeFilePrefix(raw: String): String {
    val cleaned = raw.replace(Regex("[^A-Za-z0-9_\\-]"), "_").trim('_')
    return cleaned.ifEmpty { "MaimaiData" }
}
