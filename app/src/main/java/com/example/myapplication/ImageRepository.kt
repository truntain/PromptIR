package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow
import android.graphics.Color as AndroidColor

/** Cạnh dài tối đa của ảnh người dùng chọn, tránh OutOfMemory và thời gian tiling quá lâu. */
private const val MAX_USER_IMAGE_DIMENSION = 1024
private const val PROJECTS_DIR_NAME = "studio_projects"

/**
 * Các chế độ lọc & khôi phục ảnh dành cho người dùng.
 * Người dùng chọn chế độ này để áp dụng bộ lọc làm rõ lên chính bức ảnh đang mở
 * (KHÔNG tự ý đổi sang ảnh mẫu khác).
 */
enum class RestorationFilterMode(
    val key: String,
    val title: String,
    val subtitle: String
) {
    AUTO_AI(
        key = "auto_ai",
        title = "Tự động AI",
        subtitle = "Tối ưu toàn diện"
    ),
    DEBLUR_SHARPEN(
        key = "deblur_sharpen",
        title = "Khử mờ & Làm nét",
        subtitle = "Rõ chi tiết, khuôn mặt"
    ),
    DENOISE_SMOOTH(
        key = "denoise_smooth",
        title = "Khử nhiễu hạt",
        subtitle = "Làm mịn hạt, giữ cạnh"
    ),
    LOW_LIGHT_BOOST(
        key = "low_light_boost",
        title = "Tăng sáng",
        subtitle = "Cứu vùng tối thiếu sáng"
    ),
    DEHAZE_CLARITY(
        key = "dehaze_clarity",
        title = "Khử sương & Trong ảnh",
        subtitle = "Tăng tương phản, màu sắc"
    ),
    DERAIN_CLEAN(
        key = "derain_clean",
        title = "Khử mưa & Vệt xước",
        subtitle = "Làm sạch vệt sọc, bụi"
    );

    companion object {
        fun fromKey(key: String): RestorationFilterMode =
            entries.firstOrNull { it.key == key } ?: AUTO_AI
    }
}

/**
 * Dự án ảnh đã chỉnh sửa (hiển thị ở mục "Dự án đã chỉnh sửa" trên màn hình chính kiểu CapCut).
 */
data class EditedProject(
    val id: String,
    val title: String,
    val modeKey: String,
    val modeTitle: String,
    val createdAt: Long,
    val updatedAt: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val durationMs: Long,
    val initialPsnr: Double? = null,
    val restoredPsnr: Double? = null,
    val thumbnail: Bitmap
) {
    /** Dòng thông tin phụ bên dưới tên dự án (kiểu CapCut: "1.2MB | 0.8s" hoặc "512×512 | 0.8s") */
    val metaSubtitle: String
        get() {
            val sizeStr = if (sizeBytes >= 1024 * 1024) {
                String.format(Locale.US, "%.1fMB", sizeBytes / (1024.0 * 1024.0))
            } else {
                String.format(Locale.US, "%dKB", (sizeBytes / 1024L).coerceAtLeast(1L))
            }
            val timeStr = if (durationMs < 1000) {
                "${durationMs}ms"
            } else {
                String.format(Locale.US, "%.1fs", durationMs / 1000.0)
            }
            return "$sizeStr | $timeStr"
        }
}

/** Đọc ảnh trong thư mục assets, trả về null nếu không tồn tại hoặc lỗi giải mã. */
fun loadAssetBitmap(context: Context, assetPath: String): Bitmap? = try {
    context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
} catch (_: Exception) {
    null
}

/** Giải mã ảnh từ thư viện, tự thu nhỏ nếu cạnh dài vượt quá [MAX_USER_IMAGE_DIMENSION]. */
fun decodeUserImage(context: Context, uri: Uri): Bitmap? = try {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = true
        val w = info.size.width
        val h = info.size.height
        val longest = maxOf(w, h)
        if (longest > MAX_USER_IMAGE_DIMENSION) {
            val scale = MAX_USER_IMAGE_DIMENSION.toFloat() / longest
            decoder.setTargetSize(
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1)
            )
        }
    }
} catch (_: Exception) {
    null
}

/** Tạo ảnh thu nhỏ vuông (center-crop) cho dải ảnh mẫu hoặc lưới dự án. */
fun Bitmap.toSquareThumbnail(sizePx: Int = 220): Bitmap =
    ThumbnailUtils.extractThumbnail(this, sizePx, sizePx)

/** "sample_1_bus.png" -> "Bus" */
fun prettySampleLabel(fileName: String): String {
    val base = fileName.substringBeforeLast('.')
    val parts = base.split('_')
    val name = if (parts.size >= 3 && parts[0] == "sample") parts.drop(2).joinToString(" ") else base
    return name.replaceFirstChar { it.uppercase() }
}

/**
 * Lưu ảnh PNG vào thư viện tại Pictures/PromptIR.
 * minSdk 29 nên dùng MediaStore với RELATIVE_PATH, không cần quyền ghi bộ nhớ.
 */
fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PromptIR")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        val written = resolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        } ?: false
        if (!written) error("Không ghi được dữ liệu ảnh")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (_: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

// ============================================================================
// LƯU TRỮ DỰ ÁN ĐÃ CHỈNH SỬA (CAPCUT-STYLE LOCAL PROJECTS HISTORY)
// ============================================================================

private fun getProjectsRootDir(context: Context): File =
    File(context.filesDir, PROJECTS_DIR_NAME).apply { if (!exists()) mkdirs() }

/**
 * Đọc danh sách các dự án đã chỉnh sửa từ bộ nhớ trong, sắp xếp mới nhất lên đầu.
 */
fun loadSavedProjects(context: Context): List<EditedProject> {
    val root = getProjectsRootDir(context)
    val folders = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
    val projects = mutableListOf<EditedProject>()

    for (dir in folders) {
        try {
            val metaFile = File(dir, "meta.json")
            val thumbFile = File(dir, "thumb.jpg")
            val afterFile = File(dir, "after.png")
            if (!metaFile.exists() || !afterFile.exists()) continue

            val json = JSONObject(metaFile.readText())
            val thumbBitmap = if (thumbFile.exists()) {
                BitmapFactory.decodeFile(thumbFile.absolutePath)
            } else {
                BitmapFactory.decodeFile(afterFile.absolutePath)?.toSquareThumbnail(240)
            } ?: continue

            val psnrBefore = if (json.has("initialPsnr")) json.optDouble("initialPsnr") else Double.NaN
            val psnrAfter = if (json.has("restoredPsnr")) json.optDouble("restoredPsnr") else Double.NaN

            projects.add(
                EditedProject(
                    id = json.optString("id", dir.name),
                    title = json.optString("title", dir.name),
                    modeKey = json.optString("modeKey", RestorationFilterMode.AUTO_AI.key),
                    modeTitle = json.optString("modeTitle", RestorationFilterMode.AUTO_AI.title),
                    createdAt = json.optLong("createdAt", dir.lastModified()),
                    updatedAt = json.optLong("updatedAt", dir.lastModified()),
                    width = json.optInt("width", thumbBitmap.width),
                    height = json.optInt("height", thumbBitmap.height),
                    sizeBytes = json.optLong("sizeBytes", afterFile.length()),
                    durationMs = json.optLong("durationMs", 0L),
                    initialPsnr = psnrBefore.takeIf { !it.isNaN() },
                    restoredPsnr = psnrAfter.takeIf { !it.isNaN() },
                    thumbnail = thumbBitmap
                )
            )
        } catch (_: Exception) {
            // Bỏ qua thư mục dự án bị hỏng
        }
    }

    return projects.sortedByDescending { it.updatedAt }
}

/**
 * Lưu mới hoặc cập nhật dự án ảnh đã khôi phục vào lịch sử (Projects).
 * Lưu tách biệt `before.png` (ảnh gốc), `base.png` (ảnh nền AI) và `after.png` (ảnh đã áp bộ lọc)
 * để khi mở lại dự án và đổi bộ lọc khác không bị chồng bộ lọc lên nhau.
 */
fun saveOrUpdateEditedProject(
    context: Context,
    existingId: String?,
    customTitle: String?,
    before: Bitmap,
    neuralBase: Bitmap,
    after: Bitmap,
    mode: RestorationFilterMode,
    durationMs: Long,
    initialPsnr: Double?,
    restoredPsnr: Double?
): EditedProject? = try {
    val now = System.currentTimeMillis()
    val projectId = existingId ?: "proj_$now"
    val dir = File(getProjectsRootDir(context), projectId).apply { if (!exists()) mkdirs() }

    val beforeFile = File(dir, "before.png")
    val baseFile = File(dir, "base.png")
    val afterFile = File(dir, "after.png")
    val thumbFile = File(dir, "thumb.jpg")
    val metaFile = File(dir, "meta.json")

    // Chỉ ghi lại ảnh gốc nếu chưa tồn tại hoặc tạo dự án mới
    if (!beforeFile.exists() || existingId == null) {
        FileOutputStream(beforeFile).use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    FileOutputStream(baseFile).use { neuralBase.compress(Bitmap.CompressFormat.PNG, 100, it) }
    FileOutputStream(afterFile).use { after.compress(Bitmap.CompressFormat.PNG, 100, it) }

    val thumb = after.toSquareThumbnail(256)
    FileOutputStream(thumbFile).use { thumb.compress(Bitmap.CompressFormat.JPEG, 90, it) }

    val oldMeta = if (metaFile.exists()) runCatching { JSONObject(metaFile.readText()) }.getOrNull() else null
    val createdAt = oldMeta?.optLong("createdAt", now) ?: now
    val defaultCode = SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date(createdAt))
    val title = customTitle?.takeIf { it.isNotBlank() }
        ?: oldMeta?.optString("title")?.takeIf { it.isNotBlank() }
        ?: defaultCode

    val sizeBytes = beforeFile.length() + afterFile.length()

    val json = JSONObject().apply {
        put("id", projectId)
        put("title", title)
        put("modeKey", mode.key)
        put("modeTitle", mode.title)
        put("createdAt", createdAt)
        put("updatedAt", now)
        put("width", after.width)
        put("height", after.height)
        put("sizeBytes", sizeBytes)
        put("durationMs", durationMs)
        if (initialPsnr != null) put("initialPsnr", initialPsnr)
        if (restoredPsnr != null) put("restoredPsnr", restoredPsnr)
    }
    metaFile.writeText(json.toString())

    EditedProject(
        id = projectId,
        title = title,
        modeKey = mode.key,
        modeTitle = mode.title,
        createdAt = createdAt,
        updatedAt = now,
        width = after.width,
        height = after.height,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        initialPsnr = initialPsnr,
        restoredPsnr = restoredPsnr,
        thumbnail = thumb
    )
} catch (_: Exception) {
    null
}

/** Bộ 3 ảnh của một dự án đã lưu: Ảnh gốc (before), Ảnh nền AI (neuralBase), Ảnh kết quả (after). */
data class LoadedProjectImages(
    val before: Bitmap,
    val neuralBase: Bitmap,
    val after: Bitmap
)

/**
 * Tải bộ ảnh (Before, NeuralBase, After) của một dự án đã lưu để mở lại trong trình chỉnh sửa.
 */
fun loadProjectBitmaps(context: Context, projectId: String): LoadedProjectImages? = try {
    val dir = File(getProjectsRootDir(context), projectId)
    val beforeFile = File(dir, "before.png")
    val baseFile = File(dir, "base.png")
    val afterFile = File(dir, "after.png")
    if (!beforeFile.exists() || !afterFile.exists()) {
        null
    } else {
        val before = BitmapFactory.decodeFile(beforeFile.absolutePath)
        val after = BitmapFactory.decodeFile(afterFile.absolutePath)
        val base = if (baseFile.exists()) {
            BitmapFactory.decodeFile(baseFile.absolutePath) ?: after
        } else {
            after
        }
        if (before != null && after != null && base != null) {
            LoadedProjectImages(before = before, neuralBase = base, after = after)
        } else {
            null
        }
    }
} catch (_: Exception) {
    null
}

/** Xóa một dự án đã lưu khỏi lịch sử. */
fun deleteSavedProject(context: Context, projectId: String): Boolean = try {
    val dir = File(getProjectsRootDir(context), projectId)
    dir.deleteRecursively()
} catch (_: Exception) {
    false
}

// ============================================================================
// BỘ LỌC TĂNG CƯỜNG CHUYÊN BIỆT THEO CHẾ ĐỘ (CHẠY SAU PROMPT-IR HOẶC TRỰC TIẾP)
// ============================================================================

/**
 * Áp dụng thuật toán lọc/tinh chỉnh hậu kỳ chuyên biệt theo [mode] trên ảnh đã qua mạng PromptIR
 * để mỗi chế độ (Tự động AI, Khử mờ, Khử nhiễu, Tăng sáng, Khử sương, Khử mưa) tạo ra hiệu quả
 * thị giác rõ rệt và đúng ý người dùng trên chính bức ảnh của họ.
 */
fun applyRestorationModeFilter(
    neuralRestored: Bitmap,
    mode: RestorationFilterMode
): Bitmap {
    val w = neuralRestored.width
    val h = neuralRestored.height
    val src = IntArray(w * h)
    neuralRestored.getPixels(src, 0, w, 0, 0, w, h)
    val dst = IntArray(w * h)

    fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    when (mode) {
        RestorationFilterMode.AUTO_AI -> {
            // Tự động AI: Giữ kết quả gốc của mạng PromptIR kèm cân bằng nhẹ độ trong (micro-contrast)
            for (i in src.indices) {
                val p = src[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                dst[i] = pack(
                    ((r - 128) * 1.04f + 128f).toInt(),
                    ((g - 128) * 1.04f + 128f).toInt(),
                    ((b - 128) * 1.04f + 128f).toInt()
                )
            }
        }

        RestorationFilterMode.DEBLUR_SHARPEN -> {
            // Khử mờ & Làm nét: Unsharp Mask (tăng cường chi tiết biên 3x3) giúp chữ, khuôn mặt, vật thể sắc nét
            val amount = 0.65f
            for (y in 0 until h) {
                val y0 = (y - 1).coerceAtLeast(0) * w
                val y1 = y * w
                val y2 = (y + 1).coerceAtMost(h - 1) * w
                for (x in 0 until w) {
                    val x0 = (x - 1).coerceAtLeast(0)
                    val x2 = (x + 1).coerceAtMost(w - 1)

                    val c = src[y1 + x]
                    val n = src[y0 + x]
                    val s = src[y2 + x]
                    val l = src[y1 + x0]
                    val rPx = src[y1 + x2]

                    val cr = (c shr 16) and 0xFF
                    val cg = (c shr 8) and 0xFF
                    val cb = c and 0xFF

                    val blurR = (((n shr 16) and 0xFF) + ((s shr 16) and 0xFF) + ((l shr 16) and 0xFF) + ((rPx shr 16) and 0xFF)) * 0.25f
                    val blurG = (((n shr 8) and 0xFF) + ((s shr 8) and 0xFF) + ((l shr 8) and 0xFF) + ((rPx shr 8) and 0xFF)) * 0.25f
                    val blurB = ((n and 0xFF) + (s and 0xFF) + (l and 0xFF) + (rPx and 0xFF)) * 0.25f

                    dst[y1 + x] = pack(
                        (cr + (cr - blurR) * amount).toInt(),
                        (cg + (cg - blurG) * amount).toInt(),
                        (cb + (cb - blurB) * amount).toInt()
                    )
                }
            }
        }

        RestorationFilterMode.DENOISE_SMOOTH -> {
            // Khử nhiễu hạt: Bộ lọc bảo toàn cạnh 5-tap (Unrolled Edge-Preserving Bilateral) chạy tức thì < 40ms
            val threshold = 84
            for (y in 0 until h) {
                val y0 = (y - 1).coerceAtLeast(0) * w
                val y1 = y * w
                val y2 = (y + 1).coerceAtMost(h - 1) * w
                for (x in 0 until w) {
                    val x0 = (x - 1).coerceAtLeast(0)
                    val x2 = (x + 1).coerceAtMost(w - 1)

                    val center = src[y1 + x]
                    val cr = (center shr 16) and 0xFF
                    val cg = (center shr 8) and 0xFF
                    val cb = center and 0xFF

                    val n = src[y0 + x]
                    val s = src[y2 + x]
                    val l = src[y1 + x0]
                    val rPx = src[y1 + x2]

                    val nr = (n shr 16) and 0xFF; val ng = (n shr 8) and 0xFF; val nb = n and 0xFF
                    val sr = (s shr 16) and 0xFF; val sg = (s shr 8) and 0xFF; val sb = s and 0xFF
                    val lr = (l shr 16) and 0xFF; val lg = (l shr 8) and 0xFF; val lb = l and 0xFF
                    val rr = (rPx shr 16) and 0xFF; val rg = (rPx shr 8) and 0xFF; val rb = rPx and 0xFF

                    var sumR = cr * 3
                    var sumG = cg * 3
                    var sumB = cb * 3
                    var weight = 3

                    if (abs(nr - cr) + abs(ng - cg) + abs(nb - cb) < threshold) { sumR += nr; sumG += ng; sumB += nb; weight++ }
                    if (abs(sr - cr) + abs(sg - cg) + abs(sb - cb) < threshold) { sumR += sr; sumG += sg; sumB += sb; weight++ }
                    if (abs(lr - cr) + abs(lg - cg) + abs(lb - cb) < threshold) { sumR += lr; sumG += lg; sumB += lb; weight++ }
                    if (abs(rr - cr) + abs(rg - cg) + abs(rb - cb) < threshold) { sumR += rr; sumG += rg; sumB += rb; weight++ }

                    dst[y1 + x] = pack(sumR / weight, sumG / weight, sumB / weight)
                }
            }
        }

        RestorationFilterMode.LOW_LIGHT_BOOST -> {
            // Tăng sáng: Đường cong Gamma thích ứng (0.72) cứu chi tiết vùng tối + tăng nhẹ độ tươi màu
            val lut = IntArray(256) { i ->
                val norm = i / 255.0
                val lifted = norm.pow(0.72) * 0.92 + (norm * 0.08)
                (lifted * 255.0).toInt().coerceIn(0, 255)
            }
            for (i in src.indices) {
                val p = src[i]
                val r = lut[(p shr 16) and 0xFF]
                val g = lut[(p shr 8) and 0xFF]
                val b = lut[p and 0xFF]
                val avg = (r + g + b) / 3
                // Tăng nhẹ độ bão hòa màu khi nâng sáng để ảnh không bị bệt xám
                dst[i] = pack(
                    (avg + (r - avg) * 1.12f).toInt(),
                    (avg + (g - avg) * 1.12f).toInt(),
                    (avg + (b - avg) * 1.12f).toInt()
                )
            }
        }

        RestorationFilterMode.DEHAZE_CLARITY -> {
            // Khử sương & Trong ảnh: Kéo dải động (Black-point & S-curve contrast) và tăng độ trong trẻo
            var minLum = 255
            var maxLum = 0
            for (i in src.indices step 16) {
                val p = src[i]
                val lum = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }
            val blackFloor = (minLum * 0.45f).toInt().coerceIn(0, 45)
            val span = (255 - blackFloor).coerceAtLeast(1)

            for (i in src.indices) {
                val p = src[i]
                val r = (((p shr 16) and 0xFF) - blackFloor) * 255 / span
                val g = (((p shr 8) and 0xFF) - blackFloor) * 255 / span
                val b = ((p and 0xFF) - blackFloor) * 255 / span

                val cr = ((r - 128) * 1.14f + 128f).toInt()
                val cg = ((g - 128) * 1.14f + 128f).toInt()
                val cb = ((b - 128) * 1.14f + 128f).toInt()
                val avg = (cr + cg + cb) / 3
                dst[i] = pack(
                    (avg + (cr - avg) * 1.10f).toInt(),
                    (avg + (cg - avg) * 1.10f).toInt(),
                    (avg + (cb - avg) * 1.10f).toInt()
                )
            }
        }

        RestorationFilterMode.DERAIN_CLEAN -> {
            // Khử mưa & Vệt xước: Lọc loại bỏ đột biến sáng ngang (streak suppression) kết hợp giữ chi tiết dọc
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val x0 = (x - 1).coerceAtLeast(0)
                    val x2 = (x + 1).coerceAtMost(w - 1)
                    val p0 = src[row + x0]
                    val p1 = src[row + x]
                    val p2 = src[row + x2]

                    val r0 = (p0 shr 16) and 0xFF
                    val r1 = (p1 shr 16) and 0xFF
                    val r2 = (p2 shr 16) and 0xFF

                    val g0 = (p0 shr 8) and 0xFF
                    val g1 = (p1 shr 8) and 0xFF
                    val g2 = (p2 shr 8) and 0xFF

                    val b0 = p0 and 0xFF
                    val b1 = p1 and 0xFF
                    val b2 = p2 and 0xFF

                    // Trung vị ngang 3 điểm giúp triệt tiêu vệt mưa mảnh mà không làm nhòe biên ngang
                    val medR = maxOf(minOf(r0, r1), minOf(maxOf(r0, r1), r2))
                    val medG = maxOf(minOf(g0, g1), minOf(maxOf(g0, g1), g2))
                    val medB = maxOf(minOf(b0, b1), minOf(maxOf(b0, b1), b2))

                    dst[row + x] = pack(
                        (r1 * 0.55f + medR * 0.45f).toInt(),
                        (g1 * 0.55f + medG * 0.45f).toInt(),
                        (b1 * 0.55f + medB * 0.45f).toInt()
                    )
                }
            }
        }
    }

    val out = createBitmap(w, h)
    out.setPixels(dst, 0, w, 0, 0, w, h)
    return out
}

/**
 * Sinh cặp ảnh (suy giảm, sạch) tổng hợp khi thư mục assets trống hoặc thiếu file.
 */
fun createSyntheticPair(categoryKey: String): Pair<Bitmap, Bitmap> {
    val width = 512
    val height = 512

    val gtBitmap = createBitmap(width, height)
    val canvas = Canvas(gtBitmap)
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = AndroidColor.rgb(224, 247, 250) })
    canvas.drawCircle(150f, 150f, 80f, Paint().apply { color = AndroidColor.rgb(255, 87, 34); isAntiAlias = true })
    canvas.drawRect(250f, 200f, 450f, 400f, Paint().apply { color = AndroidColor.rgb(63, 81, 181); isAntiAlias = true })
    canvas.drawText(
        "PromptIR ($categoryKey)", 40f, 460f,
        Paint().apply {
            color = AndroidColor.rgb(27, 94, 32)
            textSize = 36f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
    )

    val degraded = gtBitmap.copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(width * height)
    degraded.getPixels(pixels, 0, width, 0, 0, width, height)
    val random = Random(42)

    fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    when (categoryKey) {
        "1_noise" -> {
            for (i in pixels.indices) {
                val p = pixels[i]
                val n = (random.nextGaussian() * 35).toInt()
                pixels[i] = rgb(
                    (((p shr 16) and 0xFF) + n).coerceIn(0, 255),
                    (((p shr 8) and 0xFF) + n).coerceIn(0, 255),
                    ((p and 0xFF) + n).coerceIn(0, 255)
                )
            }
            degraded.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        "2_rain" -> {
            val rainCanvas = Canvas(degraded)
            val rainPaint = Paint().apply {
                color = AndroidColor.argb(208, 255, 255, 255)
                strokeWidth = 3f
                isAntiAlias = true
            }
            repeat(350) {
                val x = random.nextInt(width).toFloat()
                val y = random.nextInt(height).toFloat()
                rainCanvas.drawLine(x, y, x - 12f, y + 28f, rainPaint)
            }
        }
        "3_haze" -> {
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] = rgb(
                    (((p shr 16) and 0xFF) * 0.6 + 92).toInt().coerceIn(0, 255),
                    (((p shr 8) and 0xFF) * 0.6 + 92).toInt().coerceIn(0, 255),
                    ((p and 0xFF) * 0.6 + 92).toInt().coerceIn(0, 255)
                )
            }
            degraded.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        "4_blur" -> {
            for (y in 0 until height) {
                for (x in 0 until width - 6) {
                    var r = 0
                    var g = 0
                    var b = 0
                    for (dx in 0 until 7) {
                        val p = pixels[y * width + x + dx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                    pixels[y * width + x] = rgb(r / 7, g / 7, b / 7)
                }
            }
            degraded.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        "5_lowlight" -> {
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] = rgb(
                    (((p shr 16) and 0xFF) * 0.25).toInt(),
                    (((p shr 8) and 0xFF) * 0.25).toInt(),
                    ((p and 0xFF) * 0.25).toInt()
                )
            }
            degraded.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    return degraded to gtBitmap
}
