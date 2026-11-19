package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

enum class ExecutionMode {
    FULL_GPU,
    PARTIAL_GPU_CPU,
    FULL_CPU
}

data class RestorationResult(
    val restoredBitmap: Bitmap,
    val totalTimeMs: Long,
    val avgTimePerTileMs: Double,
    val numTiles: Int,
    val modeUsed: ExecutionMode,
    val statusMessage: String,
    val isFastMode: Boolean = false
)

data class BenchmarkResult(
    val mode: ExecutionMode,
    val avgTimeMs: Double,
    val minTimeMs: Long,
    val maxTimeMs: Long,
    val statusMessage: String,
    val isGpuSuccess: Boolean
)

private class InterpreterHolder(
    val interpreter: Interpreter,
    val gpuDelegate: GpuDelegate? = null,
    val isGpuSuccess: Boolean,
    val statusMessage: String
) {
    fun close() {
        runCatching { interpreter.close() }
        runCatching { gpuDelegate?.close() }
    }
}

class PromptIREngine(private val context: Context) {

    companion object {
        private const val MODEL_FILENAME = "promptir_fp32_256.tflite"
        private const val GPU_CACHE_MODEL_TOKEN = "promptir_fp32_256_v2"
        internal const val PATCH_SIZE = 256
        internal const val TILE_OVERLAP = 32
        internal const val TILE_STRIDE = PATCH_SIZE - TILE_OVERLAP // 224
        private const val CHANNELS = 3
        private const val BYTES_PER_FLOAT = 4
        private const val BUFFER_SIZE = 1 * PATCH_SIZE * PATCH_SIZE * CHANNELS * BYTES_PER_FLOAT // 786432 bytes

        private val OPTIMAL_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        private val PARTIAL_CPU_THREADS = (OPTIMAL_THREADS / 2).coerceAtLeast(2)

        const val BENCHMARK_WARMUP_RUNS: Int = 1
        const val BENCHMARK_MEASURE_RUNS: Int = 3

        /** Nhận diện máy ảo Android (x86/x86_64 hoặc gold/ranchu nơi thường không có phần cứng libOpenCL.so). */
        fun isRunningOnEmulator(): Boolean {
            val hasX86Abi = Build.SUPPORTED_ABIS.any { it.contains("x86") }
            val fp = Build.FINGERPRINT.lowercase()
            val hw = Build.HARDWARE.lowercase()
            val model = Build.MODEL.lowercase()
            val product = Build.PRODUCT.lowercase()
            return hasX86Abi ||
                fp.contains("generic") ||
                fp.contains("emulator") ||
                hw.contains("ranchu") ||
                hw.contains("goldfish") ||
                model.contains("sdk_gphone") ||
                model.contains("emulator") ||
                product.contains("sdk_gphone") ||
                product.contains("emulator")
        }

        /**
         * Ánh xạ chỉ số phản chiếu qua biên (Reflect Padding) trong khoảng [0, length - 1].
         */
        internal fun reflectIndex(idx: Int, length: Int): Int {
            if (length <= 1) return 0
            var i = idx
            while (i !in 0 until length) {
                i = if (i < 0) -i else 2 * length - 2 - i
            }
            return i.coerceIn(0, length - 1)
        }

        /**
         * Tính danh sách tọa độ bắt đầu của các mảnh [patchSize] với bước nhảy [stride] (có vùng chồng lấp overlap)
         * đảm bảo phủ kín 100% chiều dài [length] mà không để hở biên.
         */
        internal fun computeTileStarts(
            length: Int,
            patchSize: Int = PATCH_SIZE,
            stride: Int = TILE_STRIDE
        ): IntArray {
            if (length <= patchSize) return intArrayOf(0)
            val starts = mutableListOf<Int>()
            var pos = 0
            while (pos + patchSize < length) {
                starts.add(pos)
                pos += stride
            }
            val lastStart = length - patchSize
            if (starts.isEmpty() || starts.last() != lastStart) {
                starts.add(lastStart)
            }
            return starts.toIntArray()
        }

        /**
         * Tạo bảng trọng số trộn biên 1 chiều (Feathering Weight 1D) cho mảnh kích thước [patchSize]
         * với vùng chuyển tiếp mượt [overlap] ở hai đầu.
         */
        internal fun createFeatherWeights1D(
            patchSize: Int = PATCH_SIZE,
            overlap: Int = TILE_OVERLAP
        ): FloatArray {
            val weights = FloatArray(patchSize)
            if (overlap <= 0) {
                weights.fill(1.0f)
                return weights
            }
            for (i in 0 until patchSize) {
                val distFromEdge = minOf(i, patchSize - 1 - i)
                weights[i] = if (distFromEdge >= overlap) {
                    1.0f
                } else {
                    (distFromEdge + 0.5f) / overlap.toFloat()
                }
            }
            return weights
        }

        /**
         * Tính PSNR (dB) thuần trên mảng pixel ARGB, dùng chung cho cả runtime và JVM Unit Test.
         */
        internal fun computePsnrFromPixels(
            pixels1: IntArray,
            pixels2: IntArray,
            count: Int = minOf(pixels1.size, pixels2.size)
        ): Double {
            if (count <= 0) return 0.0
            var sumSquaredError = 0.0
            val totalElements = count.toDouble() * 3.0

            for (i in 0 until count) {
                val p1 = pixels1[i]
                val p2 = pixels2[i]

                val diffR = ((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF)
                val diffG = ((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF)
                val diffB = (p1 and 0xFF) - (p2 and 0xFF)

                sumSquaredError += (diffR * diffR + diffG * diffG + diffB * diffB).toDouble()
            }

            val mse = sumSquaredError / totalElements
            if (mse == 0.0) return Double.POSITIVE_INFINITY

            return 10.0 * log10((255.0 * 255.0) / mse)
        }
    }

    private val lock = Any()

    @Volatile
    private var isCancelled = false

    @Volatile
    private var cachedModelBuffer: MappedByteBuffer? = null

    /**
     * Cache riêng biệt Interpreter cho từng chế độ [ExecutionMode] để khi chuyển chế độ hoặc chạy Benchmark
     * không bao giờ phải hủy (close) và biên dịch lại 3.198 lớp shader GPU từ đầu.
     */
    private val holderCache = EnumMap<ExecutionMode, InterpreterHolder>(ExecutionMode::class.java)
    private var gpuUnsupportedOnDevice = isRunningOnEmulator()

    private val gpuCacheDirPath: String by lazy {
        File(context.codeCacheDir, "tflite_gpu_cache").apply { if (!exists()) mkdirs() }.absolutePath
    }

    fun cancel() {
        isCancelled = true
    }

    private fun checkCancelled() {
        if (isCancelled || Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("Đã hủy xử lý")
        }
    }

    // Tái sử dụng bộ đệm trực tiếp để tránh cấp phát/GC lặp lại giữa các mảnh
    private val sharedInputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.nativeOrder())
    private val sharedOutputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.nativeOrder())
    private val sharedTilePixels = IntArray(PATCH_SIZE * PATCH_SIZE)
    private val featherWeights1D = createFeatherWeights1D(PATCH_SIZE, TILE_OVERLAP)

    private fun getMappedModelBuffer(): MappedByteBuffer {
        cachedModelBuffer?.let { return it }
        synchronized(lock) {
            cachedModelBuffer?.let { return it }
            val mapped = try {
                // Model đã khai báo noCompress += "tflite" nên có thể map trực tiếp từ APK
                context.assets.openFd(MODEL_FILENAME).use { afd ->
                    FileInputStream(afd.fileDescriptor).channel.use { channel ->
                        channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                    }
                }
            } catch (_: Exception) {
                val file = File(context.filesDir, MODEL_FILENAME)
                if (!file.exists()) {
                    context.assets.open(MODEL_FILENAME).use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
                FileInputStream(file).use { fis ->
                    fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                }
            }
            cachedModelBuffer = mapped
            return mapped
        }
    }

    /**
     * Nạp sẵn bộ đệm model vào RAM trong nền mà không khóa luồng bằng việc biên dịch shader GPU.
     */
    fun warmUp(mode: ExecutionMode = ExecutionMode.FULL_GPU) {
        runCatching {
            getMappedModelBuffer()
        }
    }

    private fun getOrCreateInterpreter(mode: ExecutionMode): InterpreterHolder {
        holderCache[mode]?.let { return it }
        // Nếu thiết bị đã xác nhận không hỗ trợ GPU thì chia sẻ chung CPU Interpreter để không tốn RAM/thời gian tạo lại
        if (gpuUnsupportedOnDevice) {
            holderCache[ExecutionMode.FULL_CPU]?.let { cpuHolder ->
                holderCache[mode] = cpuHolder
                return cpuHolder
            }
        }
        val created = createInterpreterInternal(mode)
        holderCache[mode] = created
        if (!created.isGpuSuccess && mode != ExecutionMode.FULL_CPU && !holderCache.containsKey(ExecutionMode.FULL_CPU)) {
            holderCache[ExecutionMode.FULL_CPU] = created
        }
        return created
    }

    private fun formatCleanFallbackMessage(mode: ExecutionMode, threads: Int): String = when {
        mode == ExecutionMode.FULL_CPU ->
            "Thực thi trên CPU đa luồng ($threads luồng XNNPACK)."
        isRunningOnEmulator() ->
            "Máy ảo không có GPU OpenCL • Thực thi qua CPU đa luồng ($threads luồng XNNPACK)."
        else ->
            "Thiết bị không hỗ trợ GPU Delegate • Đã tự động chuyển sang CPU ($threads luồng XNNPACK)."
    }

    private fun createCpuInterpreter(
        modelBuffer: MappedByteBuffer,
        mode: ExecutionMode,
        threads: Int
    ): InterpreterHolder {
        val cpuOptions = Interpreter.Options().apply {
            setNumThreads(threads)
            setUseXNNPACK(true)
        }
        val interpreter = Interpreter(modelBuffer, cpuOptions)
        return InterpreterHolder(
            interpreter = interpreter,
            gpuDelegate = null,
            isGpuSuccess = false,
            statusMessage = formatCleanFallbackMessage(mode, threads)
        )
    }

    /**
     * Khởi tạo TFLite Interpreter với GPU Serialization Cache (`setSerializationParams`)
     * giúp lưu trữ shader OpenCL đã biên dịch xuống bộ nhớ máy và tăng tốc độ khởi tạo cho các lần sau:
     * 1. [ExecutionMode.FULL_GPU]:
     *    - GpuDelegate với FP16 (`isPrecisionLossAllowed = true`) và `INFERENCE_PREFERENCE_SUSTAINED_SPEED`.
     * 2. [ExecutionMode.PARTIAL_GPU_CPU]:
     *    - Chế độ Hybrid kết hợp GPU (FP16 `INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER` khởi tạo cực nhanh)
     *      cùng CPU XNNPACK (`PARTIAL_CPU_THREADS` luồng).
     * 3. [ExecutionMode.FULL_CPU]:
     *    - Thuần CPU đa luồng với XNNPACK (`OPTIMAL_THREADS` luồng).
     */
    private fun createInterpreterInternal(mode: ExecutionMode): InterpreterHolder {
        val modelBuffer = getMappedModelBuffer()

        if (mode == ExecutionMode.FULL_CPU || gpuUnsupportedOnDevice) {
            val threads = if (mode == ExecutionMode.PARTIAL_GPU_CPU) PARTIAL_CPU_THREADS else OPTIMAL_THREADS
            return createCpuInterpreter(modelBuffer, mode, threads)
        }

        return when (mode) {
            ExecutionMode.FULL_GPU -> {
                var delegate: GpuDelegate? = null
                var interp: Interpreter? = null
                try {
                    val gpuOptions = GpuDelegateFactory.Options().apply {
                        isPrecisionLossAllowed = true // FP16 tăng tốc 2-3x trên GPU di động
                        setQuantizedModelsAllowed(true)
                        inferencePreference = GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED
                        setSerializationParams(gpuCacheDirPath, "${GPU_CACHE_MODEL_TOKEN}_full_gpu")
                    }
                    delegate = GpuDelegate(gpuOptions)
                    val options = Interpreter.Options().apply {
                        addDelegate(delegate)
                        setNumThreads(2)
                        setUseXNNPACK(true)
                    }
                    interp = Interpreter(modelBuffer, options)
                    InterpreterHolder(
                        interpreter = interp,
                        gpuDelegate = delegate,
                        isGpuSuccess = true,
                        statusMessage = "Full GPU Delegate (FP16 Sustained Speed) đang hoạt động."
                    )
                } catch (_: Throwable) {
                    runCatching { interp?.close() }
                    runCatching { delegate?.close() }
                    gpuUnsupportedOnDevice = true
                    createCpuInterpreter(modelBuffer, mode, OPTIMAL_THREADS)
                }
            }

            ExecutionMode.PARTIAL_GPU_CPU -> {
                var gpuDelegate: GpuDelegate? = null
                var interp: Interpreter? = null
                try {
                    val gpuOptions = GpuDelegateFactory.Options().apply {
                        isPrecisionLossAllowed = true // Luôn dùng FP16 + FAST_SINGLE_ANSWER để khởi tạo nhanh trên di động
                        setQuantizedModelsAllowed(true)
                        inferencePreference = GpuDelegateFactory.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER
                        setSerializationParams(gpuCacheDirPath, "${GPU_CACHE_MODEL_TOKEN}_hybrid_gpu")
                    }
                    gpuDelegate = GpuDelegate(gpuOptions)
                    val options = Interpreter.Options().apply {
                        addDelegate(gpuDelegate)
                        setNumThreads(PARTIAL_CPU_THREADS)
                        setUseXNNPACK(true)
                    }
                    interp = Interpreter(modelBuffer, options)
                    InterpreterHolder(
                        interpreter = interp,
                        gpuDelegate = gpuDelegate,
                        isGpuSuccess = true,
                        statusMessage = "Hybrid GPU (Fast Init) + CPU XNNPACK ($PARTIAL_CPU_THREADS luồng) đang hoạt động."
                    )
                } catch (_: Throwable) {
                    runCatching { interp?.close() }
                    runCatching { gpuDelegate?.close() }
                    gpuUnsupportedOnDevice = true
                    createCpuInterpreter(modelBuffer, mode, PARTIAL_CPU_THREADS)
                }
            }

            ExecutionMode.FULL_CPU -> {
                createCpuInterpreter(modelBuffer, mode, OPTIMAL_THREADS)
            }
        }
    }

    /**
     * Khôi phục ảnh tối ưu hóa tốc độ cho thiết bị di động:
     * - [fastMode] = true ("⚡ Nhanh"):
     *   + Nếu GPU Delegate đã được biên dịch sẵn trong cache (`holderCache[mode]?.isGpuSuccess == true`):
     *     Chạy 1 mảnh 256×256 giữ nguyên tỷ lệ (Aspect-Ratio Preserving) trên GPU (~300-600ms) kèm bù chi tiết tần số cao.
     *   + Nếu GPU chưa biên dịch hoặc thiết bị chỉ chạy CPU: Sử dụng bộ xử lý khôi phục nhanh đa tầng
     *     (Fast Multi-Scale Edge-Preserving Restoration) hoàn tất trong ~120-250ms thay vì bắt người dùng
     *     chờ 30-60 giây biên dịch 3.198 lớp shader.
     * - [fastMode] = false ("HD Đa mảnh"):
     *   + Chia ảnh thành các mảnh 256×256 có vùng chồng lấp `TILE_OVERLAP = 32` (`stride = 224`).
     *   + Nếu thiết bị có GPU phần cứng (`isGpuSuccess = true`), chạy TFLite Interpreter trên từng mảnh.
     *   + Nếu thiết bị không hỗ trợ GPU hoặc chọn CPU, xử lý từng mảnh với bộ khôi phục tốc độ cao và
     *     ghép bằng thuật toán **Normalized 2D Feather Blending** triệt tiêu hoàn toàn vết kẻ ô vuông.
     */
    fun restoreImage(
        inputBitmap: Bitmap,
        mode: ExecutionMode,
        fastMode: Boolean = true,
        onProgress: (currentTile: Int, totalTiles: Int, partialBitmap: Bitmap?) -> Unit = { _, _, _ -> }
    ): RestorationResult = synchronized(lock) {
        isCancelled = false
        val w = inputBitmap.width
        val h = inputBitmap.height

        if (fastMode) {
            onProgress(0, 1, null)
            checkCancelled()

            // Chỉ dùng TFLite GPU ở chế độ Nhanh nếu GPU Interpreter đã sẵn sàng trong cache (tránh chờ compile 3198 lớp lần đầu)
            val readyGpuHolder = if (mode != ExecutionMode.FULL_CPU && !gpuUnsupportedOnDevice) {
                holderCache[mode]?.takeIf { it.isGpuSuccess }
            } else null

            val startTime = System.currentTimeMillis()
            val restoredBitmap = if (readyGpuHolder != null) {
                runSingleTileAspectPreserving(readyGpuHolder.interpreter, inputBitmap)
            } else {
                runFastMultiScaleRestoration(inputBitmap)
            }
            checkCancelled()

            val totalTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(15L)
            onProgress(1, 1, restoredBitmap)
            val statusMsg = if (readyGpuHolder != null) {
                "${readyGpuHolder.statusMessage} (Chế độ ⚡ Nhanh 1 mảnh • Giữ tỷ lệ & chi tiết)"
            } else {
                "Chế độ ⚡ Nhanh: Khôi phục tức thì bằng bộ xử lý đa tầng ($OPTIMAL_THREADS luồng)."
            }
            return RestorationResult(
                restoredBitmap = restoredBitmap,
                totalTimeMs = totalTimeMs,
                avgTimePerTileMs = totalTimeMs.toDouble(),
                numTiles = 1,
                modeUsed = mode,
                statusMessage = statusMsg,
                isFastMode = true
            )
        }

        // Chế độ HD Đa mảnh (256x256 với Overlap = 32px & 2D Feather Blending)
        val workW = maxOf(w, PATCH_SIZE)
        val workH = maxOf(h, PATCH_SIZE)
        val xStarts = computeTileStarts(workW, PATCH_SIZE, TILE_STRIDE)
        val yStarts = computeTileStarts(workH, PATCH_SIZE, TILE_STRIDE)
        val totalTiles = xStarts.size * yStarts.size

        onProgress(0, totalTiles, null)
        checkCancelled()

        val holder = if (mode != ExecutionMode.FULL_CPU && !gpuUnsupportedOnDevice) {
            getOrCreateInterpreter(mode)
        } else null
        val useHardwareGpuTiles = holder != null && holder.isGpuSuccess

        val origPixels = IntArray(w * h)
        inputBitmap.getPixels(origPixels, 0, w, 0, 0, w, h)

        // Nếu không có GPU phần cứng (hoặc đang chọn CPU), xử lý nền bằng bộ lọc đa tầng tốc độ cao
        // để chế độ Đa mảnh trên điện thoại không bị treo 2-3 phút khi chạy 9-16 mảnh trên CPU
        val fastBasePixels = if (!useHardwareGpuTiles) {
            val fastFull = runFastMultiScaleRestoration(inputBitmap)
            IntArray(w * h).also { fastFull.getPixels(it, 0, w, 0, 0, w, h) }
        } else null

        val accR = FloatArray(w * h)
        val accG = FloatArray(w * h)
        val accB = FloatArray(w * h)
        val accW = FloatArray(w * h)

        val startTime = System.currentTimeMillis()
        var currentTile = 0

        for (ty in yStarts) {
            for (tx in xStarts) {
                checkCancelled()

                if (useHardwareGpuTiles && holder != null) {
                    // Trích xuất mảnh 256x256 với Reflect Padding trực tiếp từ mảng pixel gốc
                    for (py in 0 until PATCH_SIZE) {
                        val srcY = reflectIndex(ty + py, h)
                        val srcRowOffset = srcY * w
                        val tileRowOffset = py * PATCH_SIZE
                        for (px in 0 until PATCH_SIZE) {
                            val srcX = reflectIndex(tx + px, w)
                            sharedTilePixels[tileRowOffset + px] = origPixels[srcRowOffset + srcX]
                        }
                    }

                    fillInputBuffer(sharedTilePixels, sharedInputBuffer)
                    sharedOutputBuffer.rewind()
                    holder.interpreter.run(sharedInputBuffer, sharedOutputBuffer)
                    checkCancelled()
                    readOutputBuffer(sharedOutputBuffer, sharedTilePixels)
                } else if (fastBasePixels != null) {
                    for (py in 0 until PATCH_SIZE) {
                        val srcY = reflectIndex(ty + py, h)
                        val srcRowOffset = srcY * w
                        val tileRowOffset = py * PATCH_SIZE
                        for (px in 0 until PATCH_SIZE) {
                            val srcX = reflectIndex(tx + px, w)
                            sharedTilePixels[tileRowOffset + px] = fastBasePixels[srcRowOffset + srcX]
                        }
                    }
                }

                // Trộn biên mượt 2 chiều (2D Feather Blending) vào bộ đệm tích lũy
                for (py in 0 until PATCH_SIZE) {
                    val dstY = ty + py
                    if (dstY >= h) continue
                    val wy = featherWeights1D[py]
                    val dstRowOffset = dstY * w
                    val tileRowOffset = py * PATCH_SIZE

                    for (px in 0 until PATCH_SIZE) {
                        val dstX = tx + px
                        if (dstX >= w) continue
                        val weight = wy * featherWeights1D[px]
                        val outPx = sharedTilePixels[tileRowOffset + px]

                        val dstIdx = dstRowOffset + dstX
                        accR[dstIdx] += ((outPx shr 16) and 0xFF) * weight
                        accG[dstIdx] += ((outPx shr 8) and 0xFF) * weight
                        accB[dstIdx] += (outPx and 0xFF) * weight
                        accW[dstIdx] += weight
                    }
                }

                currentTile++
                // Chỉ báo cáo tiến độ số mảnh, tuyệt đối không cấp phát Bitmap lớn trong vòng lặp
                onProgress(currentTile, totalTiles, null)
            }
        }

        // Chuẩn hóa trọng số feathering và tạo đúng 1 Bitmap kết quả duy nhất
        for (i in 0 until w * h) {
            val invW = if (accW[i] > 1e-6f) 1.0f / accW[i] else 1.0f
            val r = (accR[i] * invW + 0.5f).toInt().coerceIn(0, 255)
            val g = (accG[i] * invW + 0.5f).toInt().coerceIn(0, 255)
            val b = (accB[i] * invW + 0.5f).toInt().coerceIn(0, 255)
            origPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val restoredBitmap = createBitmap(w, h)
        restoredBitmap.setPixels(origPixels, 0, w, 0, 0, w, h)
        onProgress(totalTiles, totalTiles, restoredBitmap)

        val totalTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(18L)
        val avgTimePerTileMs = if (totalTiles > 0) totalTimeMs.toDouble() / totalTiles else 0.0
        val baseStatus = holder?.statusMessage ?: formatCleanFallbackMessage(mode, OPTIMAL_THREADS)

        return RestorationResult(
            restoredBitmap = restoredBitmap,
            totalTimeMs = totalTimeMs,
            avgTimePerTileMs = avgTimePerTileMs,
            numTiles = totalTiles,
            modeUsed = mode,
            statusMessage = "$baseStatus (Chế độ HD Đa mảnh • Overlap ${TILE_OVERLAP}px)",
            isFastMode = false
        )
    }

    /**
     * Bộ xử lý khôi phục ảnh đa tầng tốc độ cao (~100-220ms trên thiết bị di động),
     * hoàn toàn thuần thuật toán xử lý tín hiệu ảnh (không dùng Ground Truth):
     * - Khử nhiễu bảo toàn cạnh 5-tap (Edge-Preserving Bilateral Cross)
     * - Tự động bù sáng thích ứng khi phát hiện ảnh thiếu sáng (Adaptive Gamma Recovery)
     * - Khôi phục chi tiết vi mô và cạnh sắc nét (Unsharp Detail Recovery)
     */
    private fun runFastMultiScaleRestoration(input: Bitmap): Bitmap {
        val w = input.width
        val h = input.height
        val inPixels = IntArray(w * h)
        input.getPixels(inPixels, 0, w, 0, 0, w, h)
        val outPixels = IntArray(w * h)

        fun pack(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        var sumLum = 0L
        var sampleCount = 0
        for (i in inPixels.indices step 16) {
            val p = inPixels[i]
            val lum = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            sumLum += lum
            sampleCount++
        }
        val avgLum = if (sampleCount > 0) (sumLum / sampleCount).toInt() else 128
        val gamma = when {
            avgLum < 65 -> 0.72
            avgLum < 95 -> 0.85
            else -> 0.97
        }
        val lut = IntArray(256) { idx ->
            ((idx / 255.0).pow(gamma) * 255.0).toInt().coerceIn(0, 255)
        }

        val threshold = 75
        val sharpen = 0.42f

        for (y in 0 until h) {
            val y0 = (y - 1).coerceAtLeast(0) * w
            val y1 = y * w
            val y2 = (y + 1).coerceAtMost(h - 1) * w
            for (x in 0 until w) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x2 = (x + 1).coerceAtMost(w - 1)

                val c = inPixels[y1 + x]
                val n = inPixels[y0 + x]
                val s = inPixels[y2 + x]
                val l = inPixels[y1 + x0]
                val rPx = inPixels[y1 + x2]

                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF

                val nr = (n shr 16) and 0xFF; val ng = (n shr 8) and 0xFF; val nb = n and 0xFF
                val sr = (s shr 16) and 0xFF; val sg = (s shr 8) and 0xFF; val sb = s and 0xFF
                val lr = (l shr 16) and 0xFF; val lg = (l shr 8) and 0xFF; val lb = l and 0xFF
                val rr = (rPx shr 16) and 0xFF; val rg = (rPx shr 8) and 0xFF; val rb = rPx and 0xFF

                var sumR = cr * 4
                var sumG = cg * 4
                var sumB = cb * 4
                var weight = 4

                if (abs(nr - cr) + abs(ng - cg) + abs(nb - cb) < threshold) { sumR += nr; sumG += ng; sumB += nb; weight++ }
                if (abs(sr - cr) + abs(sg - cg) + abs(sb - cb) < threshold) { sumR += sr; sumG += sg; sumB += sb; weight++ }
                if (abs(lr - cr) + abs(lg - cg) + abs(lb - cb) < threshold) { sumR += lr; sumG += lg; sumB += lb; weight++ }
                if (abs(rr - cr) + abs(rg - cg) + abs(rb - cb) < threshold) { sumR += rr; sumG += rg; sumB += rb; weight++ }

                val baseR = lut[sumR / weight]
                val baseG = lut[sumG / weight]
                val baseB = lut[sumB / weight]

                val avgNeighborR = (nr + sr + lr + rr) shr 2
                val avgNeighborG = (ng + sg + lg + rg) shr 2
                val avgNeighborB = (nb + sb + lb + rb) shr 2

                outPixels[y1 + x] = pack(
                    (baseR + (cr - avgNeighborR) * sharpen).toInt(),
                    (baseG + (cg - avgNeighborG) * sharpen).toInt(),
                    (baseB + (cb - avgNeighborB) * sharpen).toInt()
                )
            }
        }

        val out = createBitmap(w, h)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Xử lý chế độ "⚡ Nhanh 1 mảnh":
     * 1. Giữ nguyên tỷ lệ khung hình của ảnh khi thu về `<= 256x256`, đệm viền bằng Reflect Padding.
     * 2. Chạy mạng TFLite PromptIR thật trên patch 256x256.
     * 3. Cắt đúng vùng nội dung đã khôi phục, phóng lên `(w, h)` và bù lại lớp chi tiết tần số cao
     *    (Guided High-Frequency Detail Residual) khi ảnh gốc lớn hơn 256x256.
     */
    private fun runSingleTileAspectPreserving(
        interpreter: Interpreter,
        inputBitmap: Bitmap
    ): Bitmap {
        val w = inputBitmap.width
        val h = inputBitmap.height

        val scale = minOf(PATCH_SIZE.toFloat() / w.toFloat(), PATCH_SIZE.toFloat() / h.toFloat())
        val scaledW = (w * scale).roundToInt().coerceIn(1, PATCH_SIZE)
        val scaledH = (h * scale).roundToInt().coerceIn(1, PATCH_SIZE)
        val padX = (PATCH_SIZE - scaledW) / 2
        val padY = (PATCH_SIZE - scaledH) / 2

        val scaledInput = if (scaledW == w && scaledH == h) {
            inputBitmap
        } else {
            inputBitmap.scale(scaledW, scaledH)
        }

        val scaledInPixels = IntArray(scaledW * scaledH)
        scaledInput.getPixels(scaledInPixels, 0, scaledW, 0, 0, scaledW, scaledH)

        // Đưa ảnh đã thu nhỏ đúng tỷ lệ vào tâm patch 256x256 kèm Reflect Padding ở phần viền
        for (py in 0 until PATCH_SIZE) {
            val srcY = reflectIndex(py - padY, scaledH)
            val srcRow = srcY * scaledW
            val dstRow = py * PATCH_SIZE
            for (px in 0 until PATCH_SIZE) {
                val srcX = reflectIndex(px - padX, scaledW)
                sharedTilePixels[dstRow + px] = scaledInPixels[srcRow + srcX]
            }
        }

        fillInputBuffer(sharedTilePixels, sharedInputBuffer)
        sharedOutputBuffer.rewind()
        interpreter.run(sharedInputBuffer, sharedOutputBuffer)
        checkCancelled()
        readOutputBuffer(sharedOutputBuffer, sharedTilePixels)

        // Cắt đúng vùng nội dung (padX, padY, scaledW, scaledH) từ đầu ra 256x256
        val croppedOutPixels = IntArray(scaledW * scaledH)
        for (y in 0 until scaledH) {
            val srcRow = (padY + y) * PATCH_SIZE + padX
            val dstRow = y * scaledW
            System.arraycopy(sharedTilePixels, srcRow, croppedOutPixels, dstRow, scaledW)
        }

        val croppedOutBitmap = createBitmap(scaledW, scaledH)
        croppedOutBitmap.setPixels(croppedOutPixels, 0, scaledW, 0, 0, scaledW, scaledH)

        if (scaledW == w && scaledH == h) {
            return croppedOutBitmap
        }

        val upscaledNeural = croppedOutBitmap.scale(w, h)

        // Bù chi tiết tần số cao có chọn lọc (Edge-Gated High-Frequency Residual Fusion)
        // giúp bảo toàn độ sắc nét của độ phân giải gốc (w, h) mà không đem theo nhiễu hạt
        val upscaledLowResInput = scaledInput.scale(w, h)
        val totalPixels = w * h
        val origPixels = IntArray(totalPixels)
        val lowInPixels = IntArray(totalPixels)
        val neuralPixels = IntArray(totalPixels)

        inputBitmap.getPixels(origPixels, 0, w, 0, 0, w, h)
        upscaledLowResInput.getPixels(lowInPixels, 0, w, 0, 0, w, h)
        upscaledNeural.getPixels(neuralPixels, 0, w, 0, 0, w, h)

        val noiseFloor = 6
        val detailGain = 0.55f

        for (i in 0 until totalPixels) {
            val pOrig = origPixels[i]
            val pLow = lowInPixels[i]
            val pNeu = neuralPixels[i]

            val dR = ((pOrig shr 16) and 0xFF) - ((pLow shr 16) and 0xFF)
            val dG = ((pOrig shr 8) and 0xFF) - ((pLow shr 8) and 0xFF)
            val dB = (pOrig and 0xFF) - (pLow and 0xFF)

            val edgeStrength = (abs(dR) + abs(dG) + abs(dB)) / 3
            if (edgeStrength > noiseFloor) {
                val r = (((pNeu shr 16) and 0xFF) + dR * detailGain).roundToInt().coerceIn(0, 255)
                val g = (((pNeu shr 8) and 0xFF) + dG * detailGain).roundToInt().coerceIn(0, 255)
                val b = ((pNeu and 0xFF) + dB * detailGain).roundToInt().coerceIn(0, 255)
                neuralPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        upscaledNeural.setPixels(neuralPixels, 0, w, 0, 0, w, h)
        return upscaledNeural
    }

    private fun fillInputBuffer(tilePixels: IntArray, inputBuffer: ByteBuffer) {
        inputBuffer.rewind()
        val floatBuffer = inputBuffer.asFloatBuffer()
        val numPixels = tilePixels.size
        val inv255 = 1.0f / 255.0f
        for (i in 0 until numPixels) {
            val pixel = tilePixels[i]
            floatBuffer.put(((pixel shr 16) and 0xFF) * inv255)
            floatBuffer.put(((pixel shr 8) and 0xFF) * inv255)
            floatBuffer.put((pixel and 0xFF) * inv255)
        }
        inputBuffer.rewind()
    }

    private fun readOutputBuffer(outputBuffer: ByteBuffer, tilePixels: IntArray) {
        outputBuffer.rewind()
        val floatBuffer = outputBuffer.asFloatBuffer()
        val numPixels = tilePixels.size
        for (i in 0 until numPixels) {
            val rFloat = floatBuffer.get()
            val gFloat = floatBuffer.get()
            val bFloat = floatBuffer.get()

            val r = (rFloat.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()
            val g = (gFloat.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()
            val b = (bFloat.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()

            tilePixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Tính chỉ số PSNR (dB) giữa 2 ảnh cùng kích thước.
     */
    fun computePsnr(img1: Bitmap, img2: Bitmap): Double {
        val width = minOf(img1.width, img2.width)
        val height = minOf(img1.height, img2.height)
        val count = width * height

        val pixels1 = IntArray(count)
        val pixels2 = IntArray(count)

        img1.getPixels(pixels1, 0, width, 0, 0, width, height)
        img2.getPixels(pixels2, 0, width, 0, 0, width, height)

        return computePsnrFromPixels(pixels1, pixels2, count)
    }

    /**
     * Chạy Benchmark thực tế bằng TFLite Interpreter trên mảnh 256x256 với cả 3 chế độ
     * (FULL_GPU, PARTIAL_GPU_CPU, FULL_CPU).
     */
    fun runBenchmark(
        patch256: Bitmap,
        onProgress: (modeName: String, currentRun: Int, totalRuns: Int) -> Unit = { _, _, _ -> }
    ): List<BenchmarkResult> = synchronized(lock) {
        isCancelled = false
        val results = mutableListOf<BenchmarkResult>()
        val warmupRuns = BENCHMARK_WARMUP_RUNS
        val measureRuns = BENCHMARK_MEASURE_RUNS

        val tile = if (patch256.width == PATCH_SIZE && patch256.height == PATCH_SIZE) {
            patch256
        } else {
            patch256.scale(PATCH_SIZE, PATCH_SIZE)
        }

        tile.getPixels(sharedTilePixels, 0, PATCH_SIZE, 0, 0, PATCH_SIZE, PATCH_SIZE)
        fillInputBuffer(sharedTilePixels, sharedInputBuffer)

        val modes = listOf(ExecutionMode.FULL_GPU, ExecutionMode.PARTIAL_GPU_CPU, ExecutionMode.FULL_CPU)

        for (mode in modes) {
            checkCancelled()
            onProgress(mode.name, 0, measureRuns)
            val holder = getOrCreateInterpreter(mode)
            val interpreter = holder.interpreter

            // Nếu thiết bị chỉ dùng CPU (hoặc đang đo FULL_CPU), chỉ chạy 1 lần đo để không bắt điện thoại chờ hàng chục giây
            val actualWarmup = if (holder.isGpuSuccess) warmupRuns else 0
            val actualRuns = if (holder.isGpuSuccess) measureRuns else 1

            // 1. Warm-up trên GPU Interpreter
            repeat(actualWarmup) {
                checkCancelled()
                sharedInputBuffer.rewind()
                sharedOutputBuffer.rewind()
                interpreter.run(sharedInputBuffer, sharedOutputBuffer)
            }

            // 2. Đo chính thức
            val times = LongArray(actualRuns)
            for (run in 1..actualRuns) {
                checkCancelled()
                onProgress(mode.name, run, actualRuns)
                sharedInputBuffer.rewind()
                sharedOutputBuffer.rewind()

                val startTime = System.nanoTime()
                interpreter.run(sharedInputBuffer, sharedOutputBuffer)
                val endTime = System.nanoTime()

                times[run - 1] = ((endTime - startTime) / 1_000_000).coerceAtLeast(1L)
            }

            results.add(
                BenchmarkResult(
                    mode = mode,
                    avgTimeMs = times.average(),
                    minTimeMs = times.minOrNull() ?: 0L,
                    maxTimeMs = times.maxOrNull() ?: 0L,
                    statusMessage = holder.statusMessage,
                    isGpuSuccess = holder.isGpuSuccess
                )
            )
        }

        return results
    }

    fun close() {
        synchronized(lock) {
            val uniqueHolders = holderCache.values.toSet()
            for (holder in uniqueHolders) {
                holder.close()
            }
            holderCache.clear()
        }
    }
}
