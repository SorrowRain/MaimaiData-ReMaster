package dev.sorrowrain.maimaidata.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import dev.sorrowrain.maimaidata.R
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.SongData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Offscreen Best 50 image renderer using Android [Bitmap] + [Canvas] (1:1 layout parity with iOS `Best50Renderer.swift`).
 */
object Best50Renderer {
    private const val ITEM_WIDTH = 200f
    private const val ITEM_HEIGHT = 250f
    private const val ITEM_PADDING = 10f
    private const val VERSION_PADDING = 50f
    private const val HEADER_HEIGHT = 250f
    private const val CONTAINER_WIDTH = 2150 // ITEM_WIDTH * 10 + ITEM_PADDING * 10 + VERSION_PADDING
    private const val CONTAINER_HEIGHT = 1300 // ITEM_HEIGHT * 5 + ITEM_PADDING * 5
    private const val TOTAL_HEIGHT = 1550 // CONTAINER_HEIGHT + HEADER_HEIGHT

    suspend fun render(
        context: Context,
        playerName: String,
        songsById: Map<Int, SongData>,
        b35: List<ProberRecord>,
        b15: List<ProberRecord>,
        region: MaimaiConstants.ServerRegion = MaimaiConstants.ServerRegion.CN
    ): Bitmap? {
        val allRecords = b35 + b15
        if (allRecords.isEmpty()) return null

        val appContext = context.applicationContext
        val uniqueIds = allRecords.map { it.songId }.distinct()
        val semaphore = Semaphore(8)

        val fetchedPairs = coroutineScope {
            uniqueIds.map { songId ->
                async(Dispatchers.IO) {
                    val song = songsById[songId] ?: return@async songId to null
                    val bmp = semaphore.withPermit {
                        withTimeoutOrNull(8_000L) {
                            CoverImageManager.loadCoverBitmap(appContext, song, targetPx = 200)
                        }
                    }
                    songId to bmp
                }
            }.awaitAll()
        }

        val covers = HashMap<Int, Bitmap>(fetchedPairs.size)
        for ((id, bmp) in fetchedPairs) {
            if (bmp != null) {
                covers[id] = bmp
            }
        }

        return withContext(Dispatchers.Default) {
            drawBest50Canvas(
                context = appContext,
                playerName = playerName,
                covers = covers,
                b35 = b35,
                b15 = b15,
                region = region
            )
        }
    }

    private fun drawBest50Canvas(
        context: Context,
        playerName: String,
        covers: Map<Int, Bitmap>,
        b35: List<ProberRecord>,
        b15: List<ProberRecord>,
        region: MaimaiConstants.ServerRegion
    ): Bitmap {
        val output = Bitmap.createBitmap(CONTAINER_WIDTH, TOTAL_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }
        val metricPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11.5f
        }
        val spriteCache = HashMap<String, Bitmap?>()

        fun sprite(assetName: String): Bitmap? {
            if (spriteCache.containsKey(assetName)) return spriteCache[assetName]
            val resId = MaimaiConstants.drawableResId(assetName)
            val decoded = if (resId != null) {
                val opts = BitmapFactory.Options().apply { inScaled = false }
                BitmapFactory.decodeResource(context.resources, resId, opts)
            } else {
                null
            }
            spriteCache[assetName] = decoded
            return decoded
        }

        try {
            val fullRect = RectF(0f, 0f, CONTAINER_WIDTH.toFloat(), TOTAL_HEIGHT.toFloat())
            val bg = sprite("mmd_player_best50")
            if (bg != null) {
                canvas.drawBitmap(bg, null, fullRect, bitmapPaint)
            } else {
                val fallbackBgPaint = Paint().apply { color = Color.rgb(64, 148, 240) }
                canvas.drawRect(fullRect, fallbackBgPaint)
            }

            val placeholderCover = sprite("mmd_song_jacket_placeholder")

            // Old Version (B35: 7 columns x 5 rows)
            b35.forEachIndexed { idx, record ->
                val x = (idx % 7) * (ITEM_WIDTH + ITEM_PADDING)
                val y = HEADER_HEIGHT + (idx / 7) * (ITEM_HEIGHT + ITEM_PADDING)
                drawRecordCard(
                    canvas = canvas,
                    bitmapPaint = bitmapPaint,
                    titlePaint = titlePaint,
                    metricPaint = metricPaint,
                    sprite = ::sprite,
                    record = record,
                    cover = covers[record.songId] ?: placeholderCover,
                    region = region,
                    originX = x,
                    originY = y
                )
            }

            // New Version (B15: 3 columns x 5 rows)
            val newOriginX = (ITEM_WIDTH + ITEM_PADDING) * 7f + VERSION_PADDING
            b15.forEachIndexed { idx, record ->
                val x = newOriginX + (idx % 3) * (ITEM_WIDTH + ITEM_PADDING)
                val y = HEADER_HEIGHT + (idx / 3) * (ITEM_HEIGHT + ITEM_PADDING)
                drawRecordCard(
                    canvas = canvas,
                    bitmapPaint = bitmapPaint,
                    titlePaint = titlePaint,
                    metricPaint = metricPaint,
                    sprite = ::sprite,
                    record = record,
                    cover = covers[record.songId] ?: placeholderCover,
                    region = region,
                    originX = x,
                    originY = y
                )
            }

            val oldRating = b35.sumOf { it.computedRating }
            val newRating = b15.sumOf { it.computedRating }
            val totalRating = oldRating + newRating

            // Rating Plate
            sprite(ratingPlateName(totalRating))?.let { plate ->
                canvas.drawBitmap(plate, null, RectF(432f, 49f, 635f, 101f), bitmapPaint)
            }

            // Name Box + Rating Box
            sprite("mmd_player_name_box")?.let { box ->
                canvas.drawBitmap(box, null, RectF(432f, 110f, 734f, 156f), bitmapPaint)
            }
            sprite("mmd_player_rating_box")?.let { box ->
                canvas.drawBitmap(box, null, RectF(432f, 165f, 734f, 206f), bitmapPaint)
            }

            // Player Name (full-width arcade style when fitting within 274px)
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.DEFAULT_BOLD
                textSize = 26f
                letterSpacing = 0.06f
            }
            val fullWidthCandidate = toFullWidthName(playerName)
            val displayPlayer = if (namePaint.measureText(fullWidthCandidate) <= 274f) {
                fullWidthCandidate
            } else {
                ellipsizeToWidth(playerName, namePaint, 274f)
            }
            val nameBaseline = centeredBaseline(110f, 156f, namePaint)
            canvas.drawText(displayPlayer, 446f, nameBaseline, namePaint)

            // Total Rating Digits (right-to-left)
            if (totalRating <= 0) {
                sprite("mmd_player_num_drating_0")?.let { zeroBmp ->
                    canvas.drawBitmap(zeroBmp, null, RectF(598f, 58f, 626f, 92f), bitmapPaint)
                }
            } else {
                var rem = totalRating
                var digitIdx = 0
                while (rem > 0) {
                    val digit = rem % 10
                    rem /= 10
                    sprite("mmd_player_num_drating_$digit")?.let { dBmp ->
                        val dx = 598f - 21f * digitIdx
                        canvas.drawBitmap(dBmp, null, RectF(dx, 58f, dx + 28f, 92f), bitmapPaint)
                    }
                    digitIdx += 1
                }
            }

            // Breakdown Text
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 14f
            }
            val breakdown = "旧版本：$oldRating   现行版本：$newRating"
            val subBaseline = centeredBaseline(165f, 206f, subPaint)
            canvas.drawText(breakdown, 472f, subBaseline, subPaint)
            return output
        } finally {
            spriteCache.values.forEach { it?.recycle() }
        }
    }

    private fun drawRecordCard(
        canvas: Canvas,
        bitmapPaint: Paint,
        titlePaint: Paint,
        metricPaint: Paint,
        sprite: (String) -> Bitmap?,
        record: ProberRecord,
        cover: Bitmap?,
        region: MaimaiConstants.ServerRegion,
        originX: Float,
        originY: Float
    ) {
        val cardRect = RectF(originX, originY, originX + ITEM_WIDTH, originY + ITEM_HEIGHT)
        sprite(record.ratingBoardAssetName)?.let { board ->
            canvas.drawBitmap(board, null, cardRect, bitmapPaint)
        }

        // Cover jacket (center-crop)
        val jacketRect = RectF(originX + 21f, originY + 24f, originX + 179f, originY + 179f)
        if (cover != null) {
            drawCenterCroppedBitmap(canvas, cover, jacketRect, bitmapPaint)
        }

        // Difficulty tag
        sprite(record.ratingDiffAssetName)?.let { diffBmp ->
            val w = diffBmp.width * 0.5f
            val h = diffBmp.height * 0.5f
            val left = originX + ITEM_WIDTH - w - 25f
            canvas.drawBitmap(diffBmp, null, RectF(left, originY + 30f, left + w, originY + 30f + h), bitmapPaint)
        }

        // DX / SD type badge
        sprite(record.typeAssetName)?.let { typeBmp ->
            canvas.drawBitmap(typeBmp, null, RectF(originX + 101f, originY, originX + 197f, originY + 27f), bitmapPaint)
        }

        // Rank badge
        sprite(record.rankAssetName(region))?.let { rankBmp ->
            canvas.drawBitmap(
                rankBmp,
                null,
                RectF(originX + 22f, originY + 145f, originX + 106f, originY + 177f),
                bitmapPaint
            )
        }

        // FC / FS badges
        val fcBmp = record.fcAssetName?.let(sprite)
        val fsBmp = record.fsAssetName?.let(sprite)
        if (fcBmp != null && fsBmp != null) {
            canvas.drawBitmap(fcBmp, null, RectF(originX + 115f, originY + 145f, originX + 149f, originY + 181f), bitmapPaint)
            canvas.drawBitmap(fsBmp, null, RectF(originX + 145f, originY + 145f, originX + 179f, originY + 181f), bitmapPaint)
        } else if (fcBmp != null) {
            canvas.drawBitmap(fcBmp, null, RectF(originX + 145f, originY + 145f, originX + 179f, originY + 181f), bitmapPaint)
        } else if (fsBmp != null) {
            canvas.drawBitmap(fsBmp, null, RectF(originX + 145f, originY + 145f, originX + 179f, originY + 181f), bitmapPaint)
        }

        // Song Title
        val truncatedTitle = ellipsizeToWidth(record.title, titlePaint, ITEM_WIDTH - 32f)
        val titleBaseline = centeredBaseline(originY + 186f, originY + 206f, titlePaint)
        canvas.drawText(truncatedTitle, originX + ITEM_WIDTH * 0.5f, titleBaseline, titlePaint)

        // Bottom metrics (Lv, Achievement, Ra)
        val metricBaseline = centeredBaseline(originY + 214f, originY + 232f, metricPaint)

        metricPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(String.format(Locale.US, "Lv:%.1f", record.ds), originX + 18f, metricBaseline, metricPaint)

        metricPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(String.format(Locale.US, "%.4f%%", record.achievements), originX + ITEM_WIDTH * 0.5f, metricBaseline, metricPaint)

        metricPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Ra:${record.computedRating}", originX + ITEM_WIDTH - 18f, metricBaseline, metricPaint)
    }

    private fun drawCenterCroppedBitmap(canvas: Canvas, bitmap: Bitmap, dst: RectF, paint: Paint) {
        val bw = bitmap.width
        val bh = bitmap.height
        if (bw <= 0 || bh <= 0) return
        val dstRatio = dst.width() / dst.height()
        val srcRatio = bw.toFloat() / bh.toFloat()
        val srcRect = if (srcRatio > dstRatio) {
            val cropW = (bh * dstRatio).toInt()
            val offsetX = (bw - cropW) / 2
            Rect(offsetX, 0, offsetX + cropW, bh)
        } else {
            val cropH = (bw / dstRatio).toInt()
            val offsetY = (bh - cropH) / 2
            Rect(0, offsetY, bw, offsetY + cropH)
        }
        canvas.drawBitmap(bitmap, srcRect, dst, paint)
    }

    private fun centeredBaseline(top: Float, bottom: Float, paint: Paint): Float {
        val fm = paint.fontMetrics
        return (top + bottom) * 0.5f - (fm.ascent + fm.descent) * 0.5f
    }

    private fun ellipsizeToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) {
            end -= 1
        }
        return if (end > 0) text.substring(0, end) + ellipsis else ellipsis
    }

    private fun toFullWidthName(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val mapped = when {
                ch == ' ' -> '\u3000'
                ch in '\u0021'..'\u007E' -> (ch.code + 0xFEE0).toChar()
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    internal fun ratingPlateName(rating: Int): String = when (rating) {
        in 0..999 -> "mmd_rating_plate_normal"
        in 1000..1999 -> "mmd_rating_plate_blue"
        in 2000..3999 -> "mmd_rating_plate_green"
        in 4000..6999 -> "mmd_rating_plate_orange"
        in 7000..9999 -> "mmd_rating_plate_red"
        in 10000..11999 -> "mmd_rating_plate_purple"
        in 12000..12999 -> "mmd_rating_plate_bronze"
        in 13000..13999 -> "mmd_rating_plate_silver"
        in 14000..14499 -> "mmd_rating_plate_gold"
        in 14500..14999 -> "mmd_rating_plate_platinum"
        else -> "mmd_rating_plate_rainbow"
    }
}
