package dev.sorrowrain.maimaidata.services

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Size
import coil3.toBitmap
import dev.sorrowrain.maimaidata.R
import dev.sorrowrain.maimaidata.models.SongData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Singleton Coil 3 [ImageLoader] manager with memory + disk caching in `context.cacheDir/song_covers`,
 * multi-CDN fallback composable [CoverImageView], and offscreen bitmap loader [loadCoverBitmap].
 */
object CoverImageManager {
    private const val DISK_CACHE_DIR = "song_covers"
    private const val MAX_DISK_CACHE_BYTES = 256L * 1024L * 1024L

    @Volatile
    private var singletonLoader: ImageLoader? = null

    private val coverHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", MaimaiAPI.APP_USER_AGENT)
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    fun imageLoader(context: Context): ImageLoader {
        singletonLoader?.let { return it }
        return synchronized(this) {
            singletonLoader ?: buildImageLoader(context.applicationContext).also {
                singletonLoader = it
            }
        }
    }

    private fun buildImageLoader(appContext: Context): ImageLoader {
        val cacheDir = File(appContext.cacheDir, DISK_CACHE_DIR)
        return ImageLoader.Builder(appContext)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { coverHttpClient }))
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(appContext, 0.22)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.toOkioPath())
                    .maxSizeBytes(MAX_DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    fun cacheSizeBytes(context: Context): Long {
        val loaderSize = singletonLoader?.diskCache?.size
        if (loaderSize != null && loaderSize > 0L) {
            return loaderSize
        }
        val dir = File(context.applicationContext.cacheDir, DISK_CACHE_DIR)
        return computeDirectorySize(dir)
    }

    fun clearCache(context: Context) {
        val appContext = context.applicationContext
        val oldLoader = synchronized(this) {
            singletonLoader.also { singletonLoader = null }
        }
        oldLoader?.memoryCache?.clear()
        oldLoader?.diskCache?.clear()
        oldLoader?.shutdown()
        val dir = File(appContext.cacheDir, DISK_CACHE_DIR)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    suspend fun loadCoverBitmap(
        context: Context,
        song: SongData,
        targetPx: Int = 200
    ): Bitmap? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val loader = imageLoader(appContext)
        val urls = song.coverCandidateUrls
        if (urls.isEmpty()) return@withContext null

        val memKey = if (targetPx > 0) "${song.coverCacheKey}_thumb_$targetPx" else song.coverCacheKey
        for (url in urls) {
            try {
                val requestBuilder = ImageRequest.Builder(appContext)
                    .data(url)
                    .memoryCacheKey(memKey)
                    .diskCacheKey(song.coverCacheKey)
                if (targetPx > 0) {
                    requestBuilder.size(Size(targetPx, targetPx))
                }
                val result = loader.execute(requestBuilder.build())
                if (result is SuccessResult) {
                    val bmp = result.image.toBitmap()
                    val softwareBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        bmp.config == Bitmap.Config.HARDWARE
                    ) {
                        bmp.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        bmp
                    }
                    if (softwareBmp != null) {
                        return@withContext softwareBmp
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Advance to next candidate CDN URL
            }
        }
        null
    }

    private fun computeDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                total += file.length()
            }
        }
        return total
    }
}

/**
 * Multi-source fallback jacket view that automatically tries [SongData.coverCandidateUrls] in order
 * and falls back to `R.drawable.mmd_song_jacket_placeholder` once candidates are exhausted.
 */
@Composable
fun CoverImageView(
    song: SongData?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val placeholder = painterResource(R.drawable.mmd_song_jacket_placeholder)
    if (song == null) {
        Image(
            painter = placeholder,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier
        )
        return
    }

    val urls = remember(song.id, song.remoteCoverName, song.preferRemoteCover) {
        song.coverCandidateUrls
    }
    var index by remember(song.id, song.remoteCoverName, song.preferRemoteCover) { mutableIntStateOf(0) }

    if (urls.isEmpty() || index >= urls.size) {
        Image(
            painter = placeholder,
            contentDescription = song.title,
            contentScale = contentScale,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val currentUrl = urls[index]
    val request = remember(context, currentUrl, song.coverCacheKey) {
        ImageRequest.Builder(context)
            .data(currentUrl)
            .memoryCacheKey(song.coverCacheKey)
            .diskCacheKey(song.coverCacheKey)
            .crossfade(true)
            .build()
    }

    AsyncImage(
        model = request,
        imageLoader = CoverImageManager.imageLoader(context),
        contentDescription = song.title,
        placeholder = placeholder,
        error = placeholder,
        contentScale = contentScale,
        onError = {
            index = if (index + 1 < urls.size) index + 1 else urls.size
        },
        modifier = modifier
    )
}
