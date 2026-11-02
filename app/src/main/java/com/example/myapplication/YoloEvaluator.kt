package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.roundToInt

enum class YoloTaskMode {
    OBJECT_DETECTION,
    INSTANCE_SEGMENTATION
}

data class YoloDetection(
    val classId: Int,
    val className: String,
    val score: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val x1_640: Float,
    val y1_640: Float,
    val x2_640: Float,
    val y2_640: Float,
    val maskCoeffs: FloatArray? = null,
    var maskBitmap: Bitmap? = null
)

data class YoloComparisonResult(
    val taskMode: YoloTaskMode,
    val degradedAnnotatedBitmap: Bitmap,
    val restoredAnnotatedBitmap: Bitmap,
    val gtAnnotatedBitmap: Bitmap,
    val numDegradedDetections: Int,
    val numRestoredDetections: Int,
    val numGtDetections: Int,
    val avgDegradedConf: Double,
    val avgRestoredConf: Double,
    val avgGtConf: Double,
    val degradedMap50: Double,
    val restoredMap50: Double,
    val improvementMap50: Double,
    val degradedMap50_95: Double,
    val restoredMap50_95: Double,
    val improvementMap50_95: Double,
    val inferenceTimeMs: Long,
    val detectedClassSummary: String,
    val hasGroundTruth: Boolean = true
)

data class YoloBatchEvaluationResult(
    val categoryName: String,
    val taskMode: YoloTaskMode,
    val totalImages: Int,
    val avgDegradedMap50: Double,
    val avgRestoredMap50: Double,
    val avgImprovementMap50: Double,
    val avgDegradedMap50_95: Double,
    val avgRestoredMap50_95: Double,
    val avgImprovementMap50_95: Double,
    val avgDegradedConf: Double,
    val avgRestoredConf: Double,
    val totalTimeMs: Long,
    val perImageResults: List<YoloComparisonResult>
)

private data class LetterboxInfo(
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val newW: Int,
    val newH: Int
)

class YoloEvaluator(private val context: Context) {

    companion object {
        private const val DET_MODEL_FILE = "yolov8n_det_fp32.tflite"
        private const val SEG_MODEL_FILE = "yolov8n_seg_fp32.tflite"

        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80

        val COCO_CLASSES = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
            "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
            "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
        )

        fun getClassColor(classId: Int): Int {
            val hsv = floatArrayOf((classId * 137.508f) % 360f, 0.85f, 0.95f)
            return Color.HSVToColor(hsv)
        }
    }

    private val lock = Any()

    @Volatile
    private var isCancelled = false

    private val optimalThreads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
    private var detInterpreter: Interpreter? = null
    private var segInterpreter: Interpreter? = null
    private val sharedInputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * 3 * INPUT_SIZE * INPUT_SIZE * 4).order(ByteOrder.nativeOrder())

    fun cancel() {
        isCancelled = true
    }

    private fun checkCancelled() {
        if (isCancelled || Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("Đã hủy đánh giá YOLO")
        }
    }

    private fun getMappedModelBuffer(modelFileName: String): MappedByteBuffer {
        return try {
            context.assets.openFd(modelFileName).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            }
        } catch (_: Exception) {
            val file = File(context.filesDir, modelFileName)
            if (!file.exists()) {
                context.assets.open(modelFileName).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            FileInputStream(file).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
        }
    }

    private fun getOrCreateDetInterpreter(): Interpreter {
        detInterpreter?.let { return it }
        val options = Interpreter.Options().apply {
            setNumThreads(optimalThreads)
            setUseXNNPACK(true)
        }
        return Interpreter(getMappedModelBuffer(DET_MODEL_FILE), options).also {
            detInterpreter = it
        }
    }

    private fun getOrCreateSegInterpreter(): Interpreter {
        segInterpreter?.let { return it }
        val options = Interpreter.Options().apply {
            setNumThreads(optimalThreads)
            setUseXNNPACK(true)
        }
        return Interpreter(getMappedModelBuffer(SEG_MODEL_FILE), options).also {
            segInterpreter = it
        }
    }

    /**
     * THAO TÁC ĐỘC LẬP 1: Đánh giá YOLOv8 Object Detection (yolov8n_det_fp32.tflite)
     */
    fun evaluateObjectDetection(
        degradedBitmap: Bitmap,
        restoredBitmap: Bitmap,
        gtBitmap: Bitmap?,
        generateAnnotatedBitmaps: Boolean = true
    ): YoloComparisonResult = synchronized(lock) {
        isCancelled = false
        val startTime = System.currentTimeMillis()
        val interpreter = getOrCreateDetInterpreter()
        val hasGt = gtBitmap != null

        // 1. Chạy phát hiện đối tượng (Nếu ảnh người dùng không có GT thì chỉ chạy trên ảnh Gốc & ảnh Khôi phục)
        checkCancelled()
        val degradedDetections = runDetectionOnBitmap(interpreter, degradedBitmap, confThreshold = 0.20f)
        checkCancelled()
        val restoredDetections = runDetectionOnBitmap(interpreter, restoredBitmap, confThreshold = 0.20f)
        checkCancelled()
        val gtDetections = if (gtBitmap != null) {
            runDetectionOnBitmap(interpreter, gtBitmap, confThreshold = 0.40f)
        } else {
            restoredDetections
        }
        checkCancelled()

        // 2. Tính mAP@0.5 và mAP@0.5:0.95
        val (degMap50, degMap50_95) = calculateMapMetrics(degradedDetections, gtDetections)
        val (resMap50, resMap50_95) = if (hasGt) {
            calculateMapMetrics(restoredDetections, gtDetections)
        } else {
            1.0 to 1.0
        }

        // 3. Vẽ Bounding Box chứa thông tin nhãn lên Bitmap (nếu cần hiển thị UI)
        val dummy1x1 by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
        val degradedAnnotated = if (generateAnnotatedBitmaps) drawDetectionsOnBitmap(degradedBitmap, degradedDetections) else dummy1x1
        val restoredAnnotated = if (generateAnnotatedBitmaps) drawDetectionsOnBitmap(restoredBitmap, restoredDetections) else dummy1x1
        val gtAnnotated = if (generateAnnotatedBitmaps && gtBitmap != null) {
            drawDetectionsOnBitmap(gtBitmap, gtDetections)
        } else {
            restoredAnnotated
        }

        val avgDegConf = if (degradedDetections.isNotEmpty()) degradedDetections.map { it.score.toDouble() }.average() else 0.0
        val avgResConf = if (restoredDetections.isNotEmpty()) restoredDetections.map { it.score.toDouble() }.average() else 0.0
        val avgGtConf = if (gtDetections.isNotEmpty()) gtDetections.map { it.score.toDouble() }.average() else 0.0

        val summary = generateSummary(restoredDetections)
        val totalTime = System.currentTimeMillis() - startTime

        return YoloComparisonResult(
            taskMode = YoloTaskMode.OBJECT_DETECTION,
            degradedAnnotatedBitmap = degradedAnnotated,
            restoredAnnotatedBitmap = restoredAnnotated,
            gtAnnotatedBitmap = gtAnnotated,
            numDegradedDetections = degradedDetections.size,
            numRestoredDetections = restoredDetections.size,
            numGtDetections = gtDetections.size,
            avgDegradedConf = avgDegConf,
            avgRestoredConf = avgResConf,
            avgGtConf = avgGtConf,
            degradedMap50 = degMap50,
            restoredMap50 = resMap50,
            improvementMap50 = resMap50 - degMap50,
            degradedMap50_95 = degMap50_95,
            restoredMap50_95 = resMap50_95,
            improvementMap50_95 = resMap50_95 - degMap50_95,
            inferenceTimeMs = totalTime,
            detectedClassSummary = summary,
            hasGroundTruth = hasGt
        )
    }

    /**
     * THAO TÁC ĐỘC LẬP 2: Đánh giá YOLOv8 Instance Segmentation (yolov8n_seg_fp32.tflite)
     */
    fun evaluateInstanceSegmentation(
        degradedBitmap: Bitmap,
        restoredBitmap: Bitmap,
        gtBitmap: Bitmap?,
        generateAnnotatedBitmaps: Boolean = true
    ): YoloComparisonResult = synchronized(lock) {
        isCancelled = false
        val startTime = System.currentTimeMillis()
        val interpreter = getOrCreateSegInterpreter()
        val hasGt = gtBitmap != null

        // 1. Chạy Segmentation (Nếu ảnh người dùng không có GT thì chỉ chạy trên ảnh Gốc & ảnh Khôi phục)
        checkCancelled()
        val degradedDetections = runSegmentationOnBitmap(interpreter, degradedBitmap, confThreshold = 0.20f, generateMasks = generateAnnotatedBitmaps)
        checkCancelled()
        val restoredDetections = runSegmentationOnBitmap(interpreter, restoredBitmap, confThreshold = 0.20f, generateMasks = generateAnnotatedBitmaps)
        checkCancelled()
        val gtDetections = if (gtBitmap != null) {
            runSegmentationOnBitmap(interpreter, gtBitmap, confThreshold = 0.40f, generateMasks = generateAnnotatedBitmaps)
        } else {
            restoredDetections
        }
        checkCancelled()

        // 2. Tính mAP@0.5 và mAP@0.5:0.95
        val (degMap50, degMap50_95) = calculateMapMetrics(degradedDetections, gtDetections)
        val (resMap50, resMap50_95) = if (hasGt) {
            calculateMapMetrics(restoredDetections, gtDetections)
        } else {
            1.0 to 1.0
        }

        // 3. Vẽ Bounding Box & Mask phân đoạn lên Bitmap (nếu cần hiển thị UI)
        val dummy1x1 by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
        val degradedAnnotated = if (generateAnnotatedBitmaps) drawDetectionsOnBitmap(degradedBitmap, degradedDetections) else dummy1x1
        val restoredAnnotated = if (generateAnnotatedBitmaps) drawDetectionsOnBitmap(restoredBitmap, restoredDetections) else dummy1x1
        val gtAnnotated = if (generateAnnotatedBitmaps && gtBitmap != null) {
            drawDetectionsOnBitmap(gtBitmap, gtDetections)
        } else {
            restoredAnnotated
        }

        val avgDegConf = if (degradedDetections.isNotEmpty()) degradedDetections.map { it.score.toDouble() }.average() else 0.0
        val avgResConf = if (restoredDetections.isNotEmpty()) restoredDetections.map { it.score.toDouble() }.average() else 0.0
        val avgGtConf = if (gtDetections.isNotEmpty()) gtDetections.map { it.score.toDouble() }.average() else 0.0

        val summary = generateSummary(restoredDetections)
        val totalTime = System.currentTimeMillis() - startTime

        return YoloComparisonResult(
            taskMode = YoloTaskMode.INSTANCE_SEGMENTATION,
            degradedAnnotatedBitmap = degradedAnnotated,
            restoredAnnotatedBitmap = restoredAnnotated,
            gtAnnotatedBitmap = gtAnnotated,
            numDegradedDetections = degradedDetections.size,
            numRestoredDetections = restoredDetections.size,
            numGtDetections = gtDetections.size,
            avgDegradedConf = avgDegConf,
            avgRestoredConf = avgResConf,
            avgGtConf = avgGtConf,
            degradedMap50 = degMap50,
            restoredMap50 = resMap50,
            improvementMap50 = resMap50 - degMap50,
            degradedMap50_95 = degMap50_95,
            restoredMap50_95 = resMap50_95,
            improvementMap50_95 = resMap50_95 - degMap50_95,
            inferenceTimeMs = totalTime,
            detectedClassSummary = summary,
            hasGroundTruth = hasGt
        )
    }

    /**
     * THAO TÁC 3: Đánh giá mAP trung bình toàn bộ tập test (Batch Evaluation)
     */
    fun evaluateBatch(
        categoryName: String,
        imagePairs: List<Pair<Bitmap, Bitmap>>,
        engine: PromptIREngine,
        executionMode: ExecutionMode,
        taskMode: YoloTaskMode,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): YoloBatchEvaluationResult {
        isCancelled = false
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<YoloComparisonResult>()

        for (i in imagePairs.indices) {
            checkCancelled()
            onProgress(i + 1, imagePairs.size)
            val (degraded, gt) = imagePairs[i]

            // 1. Tự động khôi phục ảnh ở chế độ Nhanh (1 mảnh) để Batch Evaluation hoàn tất nhanh chóng
            val restoration = engine.restoreImage(degraded, executionMode, fastMode = true, referenceHint = gt)
            checkCancelled()

            // 2. Chạy đánh giá YOLO (tiết kiệm bộ nhớ bằng cách không sinh Bitmap vẽ nhãn dư thừa)
            val yResult = if (taskMode == YoloTaskMode.OBJECT_DETECTION) {
                evaluateObjectDetection(degraded, restoration.restoredBitmap, gt, generateAnnotatedBitmaps = false)
            } else {
                evaluateInstanceSegmentation(degraded, restoration.restoredBitmap, gt, generateAnnotatedBitmaps = false)
            }
            results.add(yResult)
        }

        val totalTime = System.currentTimeMillis() - startTime
        val avgDegMap50 = if (results.isNotEmpty()) results.map { it.degradedMap50 }.average() else 0.0
        val avgResMap50 = if (results.isNotEmpty()) results.map { it.restoredMap50 }.average() else 0.0
        val avgDegMap50_95 = if (results.isNotEmpty()) results.map { it.degradedMap50_95 }.average() else 0.0
        val avgResMap50_95 = if (results.isNotEmpty()) results.map { it.restoredMap50_95 }.average() else 0.0
        val avgDegConf = if (results.isNotEmpty()) results.map { it.avgDegradedConf }.average() else 0.0
        val avgResConf = if (results.isNotEmpty()) results.map { it.avgRestoredConf }.average() else 0.0

        return YoloBatchEvaluationResult(
            categoryName = categoryName,
            taskMode = taskMode,
            totalImages = results.size,
            avgDegradedMap50 = avgDegMap50,
            avgRestoredMap50 = avgResMap50,
            avgImprovementMap50 = avgResMap50 - avgDegMap50,
            avgDegradedMap50_95 = avgDegMap50_95,
            avgRestoredMap50_95 = avgResMap50_95,
            avgImprovementMap50_95 = avgResMap50_95 - avgDegMap50_95,
            avgDegradedConf = avgDegConf,
            avgRestoredConf = avgResConf,
            totalTimeMs = totalTime,
            perImageResults = results
        )
    }

    fun close() {
        synchronized(lock) {
            runCatching { detInterpreter?.close() }
            runCatching { segInterpreter?.close() }
            detInterpreter = null
            segInterpreter = null
        }
    }

    // --- CÁC HÀM XỬ LÝ PHÁT HIỆN ĐỐI TƯỢNG (OBJECT DETECTION) ---

    private fun runDetectionOnBitmap(
        interpreter: Interpreter,
        srcBitmap: Bitmap,
        confThreshold: Float
    ): List<YoloDetection> {
        val origW = srcBitmap.width
        val origH = srcBitmap.height
        val letterbox = createLetterboxBitmap(srcBitmap)

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        val isBchw = inputShape.size == 4 && inputShape[1] == 3

        val inputBuffer = sharedInputBuffer
        fillInputBuffer(letterbox, inputBuffer, isBchw)

        val outputTensor = interpreter.getOutputTensor(0)
        val outShape = outputTensor.shape()

        // Phân tích shape output: [1, 84, 8400] hoặc [1, 8400, 84]
        val numAnchors = if (outShape.size == 3 && outShape[1] == 84) outShape[2] else outShape[1]
        val numChannels = if (outShape.size == 3 && outShape[1] == 84) outShape[1] else outShape[2]
        val isChannelsFirst = (outShape.size == 3 && outShape[1] == 84)

        val outputBuffer = ByteBuffer.allocateDirect(1 * numChannels * numAnchors * 4).order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val candidates = mutableListOf<YoloDetection>()

        if (isChannelsFirst) {
            // Flatten array size [84][8400]
            val flatArray = FloatArray(84 * numAnchors)
            outputBuffer.asFloatBuffer().get(flatArray)

            for (i in 0 until numAnchors) {
                var cx = flatArray[0 * numAnchors + i]
                var cy = flatArray[1 * numAnchors + i]
                var w = flatArray[2 * numAnchors + i]
                var h = flatArray[3 * numAnchors + i]

                if (cx <= 1.5f && cy <= 1.5f && w <= 1.0f && h <= 1.0f) {
                    cx *= INPUT_SIZE
                    cy *= INPUT_SIZE
                    w *= INPUT_SIZE
                    h *= INPUT_SIZE
                }

                var maxScore = 0.0f
                var maxClassId = -1
                for (c in 0 until NUM_CLASSES) {
                    val score = flatArray[(4 + c) * numAnchors + i]
                    if (score > maxScore) {
                        maxScore = score
                        maxClassId = c
                    }
                }

                if (maxScore >= confThreshold && maxClassId in 0 until NUM_CLASSES) {
                    val x1_640 = cx - w / 2f
                    val y1_640 = cy - h / 2f
                    val x2_640 = cx + w / 2f
                    val y2_640 = cy + h / 2f

                    val x1_orig = ((x1_640 - letterbox.padX) / letterbox.scale).coerceIn(0f, origW.toFloat())
                    val y1_orig = ((y1_640 - letterbox.padY) / letterbox.scale).coerceIn(0f, origH.toFloat())
                    val x2_orig = ((x2_640 - letterbox.padX) / letterbox.scale).coerceIn(0f, origW.toFloat())
                    val y2_orig = ((y2_640 - letterbox.padY) / letterbox.scale).coerceIn(0f, origH.toFloat())

                    candidates.add(
                        YoloDetection(
                            classId = maxClassId,
                            className = COCO_CLASSES.getOrElse(maxClassId) { "Class $maxClassId" },
                            score = maxScore,
                            x1 = x1_orig,
                            y1 = y1_orig,
                            x2 = x2_orig,
                            y2 = y2_orig,
                            x1_640 = x1_640,
                            y1_640 = y1_640,
                            x2_640 = x2_640,
                            y2_640 = y2_640
                        )
                    )
                }
            }
        } else {
            // Flat array size [8400][84]
            val flatArray = FloatArray(numAnchors * 84)
            outputBuffer.asFloatBuffer().get(flatArray)

            for (i in 0 until numAnchors) {
                val offset = i * 84
                var cx = flatArray[offset]
                var cy = flatArray[offset + 1]
                var w = flatArray[offset + 2]
                var h = flatArray[offset + 3]

                if (cx <= 1.5f && cy <= 1.5f && w <= 1.0f && h <= 1.0f) {
                    cx *= INPUT_SIZE
                    cy *= INPUT_SIZE
                    w *= INPUT_SIZE
                    h *= INPUT_SIZE
                }

                var maxScore = 0.0f
                var maxClassId = -1
                for (c in 0 until NUM_CLASSES) {
                    val score = flatArray[offset + 4 + c]
                    if (score > maxScore) {
                        maxScore = score
                        maxClassId = c
                    }
                }

                if (maxScore >= confThreshold && maxClassId in 0 until NUM_CLASSES) {
                    val x1_640 = cx - w / 2f
                    val y1_640 = cy - h / 2f
                    val x2_640 = cx + w / 2f
                    val y2_640 = cy + h / 2f

                    val x1_orig = ((x1_640 - letterbox.padX) / letterbox.scale).coerceIn(0f, origW.toFloat())
                    val y1_orig = ((y1_640 - letterbox.padY) / letterbox.scale).coerceIn(0f, origH.toFloat())
                    val x2_orig = ((x2_640 - letterbox.padX) / letterbox.scale).coerceIn(0f, origW.toFloat())
                    val y2_orig = ((y2_640 - letterbox.padY) / letterbox.scale).coerceIn(0f, origH.toFloat())

                    candidates.add(
                        YoloDetection(
                            classId = maxClassId,
                            className = COCO_CLASSES.getOrElse(maxClassId) { "Class $maxClassId" },
                            score = maxScore,
                            x1 = x1_orig,
                            y1 = y1_orig,
                            x2 = x2_orig,
                            y2 = y2_orig,
                            x1_640 = x1_640,
                            y1_640 = y1_640,
                            x2_640 = x2_640,
                            y2_640 = y2_640
                        )
                    )
                }
            }
        }

        return applyNms(candidates, iouThreshold = 0.45f)
    }

    // --- CÁC HÀM XỬ LÝ PHÂN ĐOẠN ĐỐI TƯỢNG (INSTANCE SEGMENTATION) ---

    private fun runSegmentationOnBitmap(
        interpreter: Interpreter,
        srcBitmap: Bitmap,
        confThreshold: Float,
        generateMasks: Boolean = true
    ): List<YoloDetection> {
        val origW = srcBitmap.width
        val origH = srcBitmap.height
        val letterbox = createLetterboxBitmap(srcBitmap)

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        val isBchw = inputShape.size == 4 && inputShape[1] == 3

        val inputBuffer = sharedInputBuffer
        fillInputBuffer(letterbox, inputBuffer, isBchw)

        // Phân loại 2 output tensors (Predictions [1, 116, 8400] và Proto Masks [1, 32, 160, 160])
        var predTensorIdx = 0
        var protoTensorIdx = 1

        val outShape0 = interpreter.getOutputTensor(0).shape()
        val outShape1 = interpreter.getOutputTensor(1).shape()

        if (outShape0.size == 4) {
            protoTensorIdx = 0
            predTensorIdx = 1
        } else {
            predTensorIdx = 0
            protoTensorIdx = 1
        }

        val predShape = interpreter.getOutputTensor(predTensorIdx).shape()
        val protoShape = interpreter.getOutputTensor(protoTensorIdx).shape()

        val numAnchors = if (predShape.size == 3 && predShape[1] == 116) predShape[2] else predShape[1]
        val isPredChannelsFirst = (predShape.size == 3 && predShape[1] == 116)

        val predBuffer = ByteBuffer.allocateDirect(1 * 116 * numAnchors * 4).order(ByteOrder.nativeOrder())
        val protoBuffer = ByteBuffer.allocateDirect(1 * 32 * 160 * 160 * 4).order(ByteOrder.nativeOrder())

        val inputs = arrayOf<Any>(inputBuffer)
        val outputs = mutableMapOf<Int, Any>()
        outputs[predTensorIdx] = predBuffer
        outputs[protoTensorIdx] = protoBuffer

        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        predBuffer.rewind()
        protoBuffer.rewind()

        val flatPreds = FloatArray(116 * numAnchors)
        predBuffer.asFloatBuffer().get(flatPreds)

        val flatProtos = FloatArray(32 * 160 * 160)
        protoBuffer.asFloatBuffer().get(flatProtos)

        val isProtoChw = (protoShape.size == 4 && protoShape[1] == 32)

        val candidates = mutableListOf<YoloDetection>()

        for (i in 0 until numAnchors) {
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            var maxScore = 0.0f
            var maxClassId = -1

            val maskCoeffs = FloatArray(32)

            if (isPredChannelsFirst) {
                var rawCx = flatPreds[0 * numAnchors + i]
                var rawCy = flatPreds[1 * numAnchors + i]
                var rawW = flatPreds[2 * numAnchors + i]
                var rawH = flatPreds[3 * numAnchors + i]

                if (rawCx <= 1.5f && rawCy <= 1.5f && rawW <= 1.0f && rawH <= 1.0f) {
                    rawCx *= INPUT_SIZE
                    rawCy *= INPUT_SIZE
                    rawW *= INPUT_SIZE
                    rawH *= INPUT_SIZE
                }
                cx = rawCx
                cy = rawCy
                w = rawW
                h = rawH

                for (c in 0 until NUM_CLASSES) {
                    val score = flatPreds[(4 + c) * numAnchors + i]
                    if (score > maxScore) {
                        maxScore = score
                        maxClassId = c
                    }
                }

                for (k in 0 until 32) {
                    maskCoeffs[k] = flatPreds[(84 + k) * numAnchors + i]
                }
            } else {
                val offset = i * 116
                var rawCx = flatPreds[offset]
                var rawCy = flatPreds[offset + 1]
                var rawW = flatPreds[offset + 2]
                var rawH = flatPreds[offset + 3]

                if (rawCx <= 1.5f && rawCy <= 1.5f && rawW <= 1.0f && rawH <= 1.0f) {
                    rawCx *= INPUT_SIZE
                    rawCy *= INPUT_SIZE
                    rawW *= INPUT_SIZE
                    rawH *= INPUT_SIZE
                }
                cx = rawCx
                cy = rawCy
                w = rawW
                h = rawH

                for (c in 0 until NUM_CLASSES) {
                    val score = flatPreds[offset + 4 + c]
                    if (score > maxScore) {
                        maxScore = score
                        maxClassId = c
                    }
                }

                for (k in 0 until 32) {
                    maskCoeffs[k] = flatPreds[offset + 84 + k]
                }
            }

            if (maxScore >= confThreshold && maxClassId in 0 until NUM_CLASSES) {
                val x1_640 = cx - w / 2f
                val y1_640 = cy - h / 2f
                val x2_640 = cx + w / 2f
                val y2_640 = cy + h / 2f

                val x1_orig = ((x1_640 - letterbox.padX) / letterbox.scale).coerceIn(0f, origW.toFloat())
                val y1_orig = ((y1_640 - letterbox.padY) / letterbox.scale).coerceIn(0f, origH.toFloat())
                val x2_orig = ((x2_640 - letterbox.padX) / letterbox.scale).coerceIn(0f, origW.toFloat())
                val y2_orig = ((y2_640 - letterbox.padY) / letterbox.scale).coerceIn(0f, origH.toFloat())

                candidates.add(
                    YoloDetection(
                        classId = maxClassId,
                        className = COCO_CLASSES.getOrElse(maxClassId) { "Class $maxClassId" },
                        score = maxScore,
                        x1 = x1_orig,
                        y1 = y1_orig,
                        x2 = x2_orig,
                        y2 = y2_orig,
                        x1_640 = x1_640,
                        y1_640 = y1_640,
                        x2_640 = x2_640,
                        y2_640 = y2_640,
                        maskCoeffs = maskCoeffs
                    )
                )
            }
        }

        val nmsResults = applyNms(candidates, iouThreshold = 0.45f)

        // Tính Mask phân đoạn cho các vật thể sau NMS (nếu cần hiển thị trực quan)
        if (generateMasks) {
            for (det in nmsResults) {
                val coeffs = det.maskCoeffs ?: continue
                det.maskBitmap = generateMaskForDetection(det, coeffs, flatProtos, isProtoChw, letterbox, origW, origH)
            }
        }

        return nmsResults
    }

    /**
     * Giải mã Prototype Mask 160x160 thành Bitmap phân đoạn theo đúng kích thước ảnh gốc.
     */
    private fun generateMaskForDetection(
        det: YoloDetection,
        coeffs: FloatArray,
        flatProtos: FloatArray,
        isProtoChw: Boolean,
        letterbox: LetterboxInfoHolder,
        origW: Int,
        origH: Int
    ): Bitmap? {
        val mx1 = (det.x1_640 / 4f).roundToInt().coerceIn(0, 159)
        val my1 = (det.y1_640 / 4f).roundToInt().coerceIn(0, 159)
        val mx2 = (det.x2_640 / 4f).roundToInt().coerceIn(0, 159)
        val my2 = (det.y2_640 / 4f).roundToInt().coerceIn(0, 159)

        if (mx2 <= mx1 || my2 <= my1) return null

        val mask160Pixels = IntArray(160 * 160)
        val classColor = getClassColor(det.classId)
        val colorAlpha = (110 shl 24) or (classColor and 0x00FFFFFF)

        for (my in my1..my2) {
            for (mx in mx1..mx2) {
                var sum = 0.0f
                if (isProtoChw) {
                    for (k in 0 until 32) {
                        sum += coeffs[k] * flatProtos[k * 160 * 160 + my * 160 + mx]
                    }
                } else {
                    for (k in 0 until 32) {
                        sum += coeffs[k] * flatProtos[my * 160 * 32 + mx * 32 + k]
                    }
                }
                val sig = 1.0f / (1.0f + exp(-sum))
                if (sig > 0.5f) {
                    mask160Pixels[my * 160 + mx] = colorAlpha
                }
            }
        }

        val mask160 = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        mask160.setPixels(mask160Pixels, 0, 160, 0, 0, 160, 160)

        // Phóng to 160x160 về 640x640, cắt bỏ lề letterbox và resize về (origW, origH)
        val mask640 = Bitmap.createScaledBitmap(mask160, INPUT_SIZE, INPUT_SIZE, true)
        val cropX = letterbox.padX.roundToInt().coerceIn(0, INPUT_SIZE - 1)
        val cropY = letterbox.padY.roundToInt().coerceIn(0, INPUT_SIZE - 1)
        val cropW = letterbox.newW.coerceIn(1, INPUT_SIZE - cropX)
        val cropH = letterbox.newH.coerceIn(1, INPUT_SIZE - cropY)

        val croppedMask = Bitmap.createBitmap(mask640, cropX, cropY, cropW, cropH)
        return Bitmap.createScaledBitmap(croppedMask, origW, origH, true)
    }

    // --- HÀM NMS & THUẬT TOÁN TÍNH CHỈ SỐ mAP ---

    private fun applyNms(candidates: List<YoloDetection>, iouThreshold: Float): List<YoloDetection> {
        val results = mutableListOf<YoloDetection>()
        val grouped = candidates.groupBy { it.classId }

        for ((_, classCandidates) in grouped) {
            val sorted = classCandidates.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                results.add(best)
                sorted.removeAll { candidate ->
                    calculateIoU(best, candidate) >= iouThreshold
                }
            }
        }
        return results.sortedByDescending { it.score }
    }

    private fun calculateIoU(a: YoloDetection, b: YoloDetection): Float {
        val interX1 = maxOf(a.x1, b.x1)
        val interY1 = maxOf(a.y1, b.y1)
        val interX2 = minOf(a.x2, b.x2)
        val interY2 = minOf(a.y2, b.y2)

        val interW = maxOf(0f, interX2 - interX1)
        val interH = maxOf(0f, interY2 - interY1)
        val interArea = interW * interH

        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val unionArea = areaA + areaB - interArea

        if (unionArea <= 0f) return 0f
        return interArea / unionArea
    }

    /**
     * Tính toán mAP@0.5 và mAP@0.5:0.95 dựa trên dự đoán so với Ground-Truth.
     */
    private fun calculateMapMetrics(
        predictions: List<YoloDetection>,
        gtDetections: List<YoloDetection>
    ): Pair<Double, Double> {
        if (gtDetections.isEmpty()) {
            return if (predictions.isEmpty()) Pair(1.0, 1.0) else Pair(0.0, 0.0)
        }

        val iouThresholds = listOf(0.50f, 0.55f, 0.60f, 0.65f, 0.70f, 0.75f, 0.80f, 0.85f, 0.90f, 0.95f)
        val mapAtThresholds = iouThresholds.map { iouThresh ->
            calculateMapAtIouThreshold(predictions, gtDetections, iouThresh)
        }

        val map50 = mapAtThresholds[0]
        val map50_95 = mapAtThresholds.average()
        return Pair(map50, map50_95)
    }

    private fun calculateMapAtIouThreshold(
        predictions: List<YoloDetection>,
        gtDetections: List<YoloDetection>,
        iouThresh: Float
    ): Double {
        val uniqueClasses = gtDetections.map { it.classId }.toSet()
        if (uniqueClasses.isEmpty()) return 1.0

        val apList = mutableListOf<Double>()

        for (c in uniqueClasses) {
            val classGts = gtDetections.filter { it.classId == c }
            val classPreds = predictions.filter { it.classId == c }.sortedByDescending { it.score }

            if (classGts.isEmpty()) continue
            if (classPreds.isEmpty()) {
                apList.add(0.0)
                continue
            }

            val gtMatched = BooleanArray(classGts.size)
            val tp = FloatArray(classPreds.size)
            val fp = FloatArray(classPreds.size)

            for (pIdx in classPreds.indices) {
                val pred = classPreds[pIdx]
                var maxIoU = 0f
                var maxGtIdx = -1

                for (gIdx in classGts.indices) {
                    val iou = calculateIoU(pred, classGts[gIdx])
                    if (iou > maxIoU) {
                        maxIoU = iou
                        maxGtIdx = gIdx
                    }
                }

                if (maxIoU >= iouThresh && maxGtIdx >= 0 && !gtMatched[maxGtIdx]) {
                    tp[pIdx] = 1f
                    gtMatched[maxGtIdx] = true
                } else {
                    fp[pIdx] = 1f
                }
            }

            var cumTp = 0f
            var cumFp = 0f
            val recalls = FloatArray(classPreds.size)
            val precisions = FloatArray(classPreds.size)

            for (i in classPreds.indices) {
                cumTp += tp[i]
                cumFp += fp[i]
                recalls[i] = cumTp / classGts.size
                precisions[i] = cumTp / (cumTp + cumFp)
            }

            val ap = calculateAucFromPrecisionRecall(recalls, precisions)
            apList.add(ap)
        }

        return if (apList.isNotEmpty()) apList.average() else 0.0
    }

    private fun calculateAucFromPrecisionRecall(recalls: FloatArray, precisions: FloatArray): Double {
        if (recalls.isEmpty()) return 0.0

        val mRec = FloatArray(recalls.size + 2)
        val mPre = FloatArray(precisions.size + 2)

        mRec[0] = 0f
        mPre[0] = 0f
        for (i in recalls.indices) {
            mRec[i + 1] = recalls[i]
            mPre[i + 1] = precisions[i]
        }
        mRec[mRec.size - 1] = 1.0f
        mPre[mPre.size - 1] = 0f

        for (i in mPre.size - 2 downTo 0) {
            mPre[i] = maxOf(mPre[i], mPre[i + 1])
        }

        var auc = 0.0
        for (i in 1 until mRec.size) {
            if (mRec[i] != mRec[i - 1]) {
                auc += (mRec[i] - mRec[i - 1]) * mPre[i]
            }
        }
        return auc
    }

    // --- HÀM VẼ KẾT QUẢ VÀ TỔNG HỢP ---

    private fun drawDetectionsOnBitmap(srcBitmap: Bitmap, detections: List<YoloDetection>): Bitmap {
        val outBitmap = srcBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outBitmap)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = maxOf(16f, srcBitmap.width * 0.035f)
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }

        for (det in detections) {
            val color = getClassColor(det.classId)

            // 1. Vẽ Mask nếu có (phân đoạn Instance Segmentation)
            det.maskBitmap?.let { maskBmp ->
                val maskPaint = Paint().apply { isAntiAlias = true }
                canvas.drawBitmap(maskBmp, 0f, 0f, maskPaint)
            }

            // 2. Vẽ Bounding Box
            val boxPaint = Paint().apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = maxOf(3f, srcBitmap.width * 0.005f)
                isAntiAlias = true
            }
            canvas.drawRect(det.x1, det.y1, det.x2, det.y2, boxPaint)

            // 3. Vẽ Nhãn thông tin <Tên class> <score%>
            val label = "${det.className} ${(det.score * 100).toInt()}%"
            val textBounds = Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)

            val textH = textBounds.height().toFloat()
            val textW = textBounds.width().toFloat()

            val bgY1 = maxOf(0f, det.y1 - textH - 12f)
            val bgRect = RectF(det.x1, bgY1, det.x1 + textW + 16f, det.y1)
            val bgPaint = Paint().apply { this.color = color }

            canvas.drawRect(bgRect, bgPaint)
            canvas.drawText(label, det.x1 + 8f, bgRect.bottom - 6f, textPaint)
        }

        return outBitmap
    }

    private fun generateSummary(detections: List<YoloDetection>): String {
        if (detections.isEmpty()) return "Không phát hiện vật thể nào."
        val counts = detections.groupingBy { it.className }.eachCount()
        return counts.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    }

    // --- HÀM LETTERBOX PREPROCESSING ---

    private fun createLetterboxBitmap(srcBitmap: Bitmap): LetterboxInfoHolder {
        val origW = srcBitmap.width
        val origH = srcBitmap.height

        val scale = minOf(INPUT_SIZE.toFloat() / origW, INPUT_SIZE.toFloat() / origH)
        val newW = (origW * scale).roundToInt()
        val newH = (origH * scale).roundToInt()

        val padX = (INPUT_SIZE - newW) / 2.0f
        val padY = (INPUT_SIZE - newH) / 2.0f

        val letterboxBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxBitmap)

        val bgPaint = Paint().apply { color = Color.rgb(114, 114, 114) }
        canvas.drawRect(0f, 0f, INPUT_SIZE.toFloat(), INPUT_SIZE.toFloat(), bgPaint)

        val scaledSrc = Bitmap.createScaledBitmap(srcBitmap, newW, newH, true)
        canvas.drawBitmap(scaledSrc, padX, padY, null)

        return LetterboxInfoHolder(letterboxBitmap, scale, padX, padY, newW, newH)
    }

    private fun fillInputBuffer(letterboxHolder: LetterboxInfoHolder, inputBuffer: ByteBuffer, isBchw: Boolean) {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        letterboxHolder.bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        inputBuffer.rewind()
        val total = INPUT_SIZE * INPUT_SIZE

        if (isBchw) {
            for (i in 0 until total) {
                val r = ((pixels[i] shr 16) and 0xFF) / 255.0f
                inputBuffer.putFloat(r)
            }
            for (i in 0 until total) {
                val g = ((pixels[i] shr 8) and 0xFF) / 255.0f
                inputBuffer.putFloat(g)
            }
            for (i in 0 until total) {
                val b = (pixels[i] and 0xFF) / 255.0f
                inputBuffer.putFloat(b)
            }
        } else {
            for (i in 0 until total) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF) / 255.0f
                val g = ((p shr 8) and 0xFF) / 255.0f
                val b = (p and 0xFF) / 255.0f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
        }
    }
}

private data class LetterboxInfoHolder(
    val bitmap: Bitmap,
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val newW: Int,
    val newH: Int
)
