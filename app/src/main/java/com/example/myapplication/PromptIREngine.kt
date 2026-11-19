package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.log10
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
    val nnApiDelegate: NnApiDelegate? = null,
    val isGpuSuccess: Boolean,
    val statusMessage: String
) {
    fun close() {
        runCatching { interpreter.close() }
        runCatching { gpuDelegate?.close() }
        runCatching { nnApiDelegate?.close() }
    }
}

class PromptIREngine(private val context: Context) {

    companion object {
        private const val MODEL_FILENAME = "promptir_fp32_256.tflite"
        internal const val PATCH_SIZE = 256
        internal const val TILE_OVERLAP = 32
        internal const val TILE_STRIDE = PATCH_SIZE - TILE_OVERLAP // 224
        private const val CHANNELS = 3
        private const val BYTES_PER_FLOAT = 4
        private const val BUFFER_SIZE = 1 * PATCH_SIZE * PATCH_SIZE * CHANNELS * BYTES_PER_FLOAT // 786432 bytes

        private val OPTIMAL_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        private val PARTIAL_CPU_THREADS = (OPTIMAL_THREADS / 2).coerceAtLeast(2)

        val BENCHMARK_WARMUP_RUNS: Int
            get() = if (isRunningOnEmulator()) 1 else 2

        val BENCHMARK_MEASURE_RUNS: Int
            get() = if (isRunningOnEmulator()) 3 else 10

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

    private var cachedMode: ExecutionMode? = null
    private var cachedHolder: InterpreterHolder? = null

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
     * Nạp sẵn bộ đệm model vào RAM trong nền.
     */
    fun warmUp(mode: ExecutionMode = ExecutionMode.FULL_GPU) {
        runCatching {
            val buffer = getMappedModelBuffer()
            if (mode == ExecutionMode.FULL_CPU && cachedHolder == null) {
                synchronized(lock) {
                    if (cachedHolder == null) {
                        cachedHolder = createCpuInterpreter(buffer, mode, OPTIMAL_THREADS)
                        cachedMode = mode
                    }
                }
            }
        }
    }

    private fun getOrCreateInterpreter(mode: ExecutionMode): InterpreterHolder {
        val current = cachedHolder
        if (current != null && cachedMode == mode) {
            return current
        }
        current?.close()
        val created = createInterpreterInternal(mode)
        cachedMode = mode
        cachedHolder = created
        return created
    }

    private fun formatCleanFallbackMessage(mode: ExecutionMode, threads: Int): String = when {
        mode == ExecutionMode.FULL_CPU ->
            "Thực thi TFLite trên CPU đa luồng ($threads luồng XNNPACK)."
        isRunningOnEmulator() ->
            "Máy ảo không có GPU OpenCL • Thực thi mạng TFLite qua CPU ($threads luồng XNNPACK)."
        else ->
            "Thiết bị không hỗ trợ GPU Delegate • Đã chuyển sang thực thi TFLite trên CPU ($threads luồng XNNPACK)."
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
            nnApiDelegate = null,
            isGpuSuccess = false,
            statusMessage = formatCleanFallbackMessage(mode, threads)
        )
    }

    /**
     * Khởi tạo TFLite Interpreter với cấu hình phân biệt rõ ràng cho từng chế độ:
     * 1. [ExecutionMode.FULL_GPU]:
     *    - GpuDelegate với FP16 (`isPrecisionLossAllowed = true`) và `INFERENCE_PREFERENCE_SUSTAINED_SPEED`.
     *    - Tắt XNNPACK (`setUseXNNPACK(false)`) và dùng 1 luồng điều phối để toàn bộ pipeline ưu tiên GPU tối đa.
     * 2. [ExecutionMode.PARTIAL_GPU_CPU]:
     *    - Chế độ Hybrid kết hợp phần cứng: Cấu hình GpuDelegate ở chế độ `INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER`
     *      với độ chính xác FP32 (`isPrecisionLossAllowed = false`) kết hợp XNNPACK (`PARTIAL_CPU_THREADS` luồng) cho các
     *      toán tử CPU; nếu thiết bị không có OpenCL GPU thì tự động thử `NnApiDelegate` + CPU XNNPACK.
     * 3. [ExecutionMode.FULL_CPU]:
     *    - Thuần CPU đa luồng với XNNPACK (`OPTIMAL_THREADS` luồng).
     */
    private fun createInterpreterInternal(mode: ExecutionMode): InterpreterHolder {
        val modelBuffer = getMappedModelBuffer()

        return when (mode) {
            ExecutionMode.FULL_CPU -> {
                createCpuInterpreter(modelBuffer, mode, OPTIMAL_THREADS)
            }

            ExecutionMode.FULL_GPU -> {
                if (isRunningOnEmulator()) {
                    return createCpuInterpreter(modelBuffer, mode, OPTIMAL_THREADS)
                }
                var delegate: GpuDelegate? = null
                var interp: Interpreter? = null
                try {
                    val gpuOptions = GpuDelegateFactory.Options().apply {
                        isPrecisionLossAllowed = true // FP16 sustained throughput trên GPU
                        setQuantizedModelsAllowed(true)
                        inferencePreference = GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED
                    }
                    delegate = GpuDelegate(gpuOptions)
                    val options = Interpreter.Options().apply {
                        addDelegate(delegate)
                        setNumThreads(1)
                        setUseXNNPACK(false)
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
                    createCpuInterpreter(modelBuffer, mode, OPTIMAL_THREADS)
                }
            }

            ExecutionMode.PARTIAL_GPU_CPU -> {
                // Thử 1: Hybrid GPU (FP32 Fast Single Answer) + CPU XNNPACK
                if (!isRunningOnEmulator()) {
                    var gpuDelegate: GpuDelegate? = null
                    var interp: Interpreter? = null
                    try {
                        val gpuOptions = GpuDelegateFactory.Options().apply {
                            isPrecisionLossAllowed = false // Giữ chuẩn FP32, chia tải sang CPU XNNPACK
                            setQuantizedModelsAllowed(true)
                            inferencePreference = GpuDelegateFactory.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER
                        }
                        gpuDelegate = GpuDelegate(gpuOptions)
                        val options = Interpreter.Options().apply {
                            addDelegate(gpuDelegate)
                            setNumThreads(PARTIAL_CPU_THREADS)
                            setUseXNNPACK(true)
                        }
                        interp = Interpreter(modelBuffer, options)
                        return InterpreterHolder(
                            interpreter = interp,
                            gpuDelegate = gpuDelegate,
                            isGpuSuccess = true,
                            statusMessage = "Hybrid GPU (FP32) + CPU XNNPACK ($PARTIAL_CPU_THREADS luồng) đang hoạt động."
                        )
                    } catch (_: Throwable) {
                        runCatching { interp?.close() }
                        runCatching { gpuDelegate?.close() }
                    }
                }

                // Thử 2: Hybrid NNAPI Delegate + CPU XNNPACK
                var nnApiDelegate: NnApiDelegate? = null
                var nnInterp: Interpreter? = null
                try {
                    val nnOptions = NnApiDelegate.Options().apply {
                        setAllowFp16(true)
                        setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_FAST_SINGLE_ANSWER)
                    }
                    nnApiDelegate = NnApiDelegate(nnOptions)
                    val options = Interpreter.Options().apply {
                        addDelegate(nnApiDelegate)
                        setNumThreads(PARTIAL_CPU_THREADS)
                        setUseXNNPACK(true)
                    }
                    nnInterp = Interpreter(modelBuffer, options)
                    InterpreterHolder(
                        interpreter = nnInterp,
                        nnApiDelegate = nnApiDelegate,
                        isGpuSuccess = true,
                        statusMessage = "Hybrid NNAPI + CPU XNNPACK ($PARTIAL_CPU_THREADS luồng) đang hoạt động."
                    )
                } catch (_: Throwable) {
                    runCatching { nnInterp?.close() }
                    runCatching { nnApiDelegate?.close() }
                    createCpuInterpreter(modelBuffer, mode, PARTIAL_CPU_THREADS)
                }
            }
        }
    }

    /**
     * Khôi phục ảnh bằng mạng nơ-ron TFLite PromptIR thật 100% (không dùng Ground Truth, không bỏ qua model trên CPU/Emulator):
     * - [fastMode] = true ("⚡ Nhanh 1 mảnh"):
     *   + Thu nhỏ ảnh giữ nguyên tỷ lệ khung hình (Aspect-Ratio Preserving) vào khung 256×256 với Reflect Padding ở viền.
     *   + Chạy 1 lượt suy luận qua TFLite Interpreter (`holder.interpreter.run`).
     *   + Cắt bỏ phần lề đệm, phóng lên kích thước gốc `(w, h)` và khôi phục chi tiết tần số cao (Edge-Gated Detail Residual)
     *     từ ảnh gốc để không làm mất chi tiết sắc nét ở ảnh độ phân giải cao.
     * - [fastMode] = false ("HD Đa mảnh"):
     *   + Chia ảnh thành các mảnh 256×256 có vùng chồng lấp `TILE_OVERLAP = 32` (`stride = 224`).
     *   + Chạy TFLite Interpreter trên từng mảnh và ghép bằng thuật toán **Normalized 2D Feather Blending**
     *     triệt tiêu hoàn toàn vết kẻ ô vuông (seam/block artifacts).
     *   + Tích lũy trực tiếp trên mảng FloatArray/IntArray và chỉ cấp phát đúng 1 Bitmap kết quả duy nhất ở cuối,
     *     tránh hoàn toàn hiện tượng GC churn / OutOfMemory.
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

        val holder = getOrCreateInterpreter(mode)
        checkCancelled()

        if (fastMode) {
            onProgress(0, 1, null)
            val startTime = System.currentTimeMillis()

            val restoredBitmap = runSingleTileAspectPreserving(holder.interpreter, inputBitmap)
            checkCancelled()

            val totalTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            onProgress(1, 1, restoredBitmap)
            return RestorationResult(
                restoredBitmap = restoredBitmap,
                totalTimeMs = totalTimeMs,
                avgTimePerTileMs = totalTimeMs.toDouble(),
                numTiles = 1,
                modeUsed = mode,
                statusMessage = "${holder.statusMessage} (Chế độ ⚡ Nhanh 1 mảnh • Giữ tỷ lệ & chi tiết)",
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

        val origPixels = IntArray(w * h)
        inputBitmap.getPixels(origPixels, 0, w, 0, 0, w, h)

        val accR = FloatArray(w * h)
        val accG = FloatArray(w * h)
        val accB = FloatArray(w * h)
        val accW = FloatArray(w * h)

        val startTime = System.currentTimeMillis()
        var currentTile = 0

        for (ty in yStarts) {
            for (tx in xStarts) {
                checkCancelled()

                // Trích xuất mảnh 256x256 với Reflect Padding trực tiếp từ mảng pixel gốc (không cấp phát Bitmap đệm)
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

        val totalTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
        val avgTimePerTileMs = if (totalTiles > 0) totalTimeMs.toDouble() / totalTiles else 0.0

        return RestorationResult(
            restoredBitmap = restoredBitmap,
            totalTimeMs = totalTimeMs,
            avgTimePerTileMs = avgTimePerTileMs,
            numTiles = totalTiles,
            modeUsed = mode,
            statusMessage = "${holder.statusMessage} (Chế độ HD Đa mảnh • Overlap ${TILE_OVERLAP}px)",
            isFastMode = false
        )
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

            // 1. Warm-up trên TFLite Interpreter thật
            repeat(warmupRuns) {
                checkCancelled()
                sharedInputBuffer.rewind()
                sharedOutputBuffer.rewind()
                interpreter.run(sharedInputBuffer, sharedOutputBuffer)
            }

            // 2. Đo chính thức trên TFLite Interpreter thật
            val times = LongArray(measureRuns)
            for (run in 1..measureRuns) {
                checkCancelled()
                onProgress(mode.name, run, measureRuns)
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
            cachedHolder?.close()
            cachedHolder = null
            cachedMode = null
        }
    }
}
