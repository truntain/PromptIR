package com.example.myapplication

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Màn hình hiện tại của ứng dụng: Trang chủ kiểu CapCut (HOME) hoặc Trình chỉnh sửa (EDITOR). */
enum class StudioScreen {
    HOME,
    EDITOR
}

/** Một bộ dữ liệu suy giảm trong assets/test_images (dùng cho phòng Lab Benchmark & ảnh mẫu Demo). */
data class DegradationCategory(
    val key: String,
    val title: String,
    val dataset: String,
    val defaultFilterMode: RestorationFilterMode
)

val degradationCategories = listOf(
    DegradationCategory("1_noise", "Ảnh nhiễu hạt", "BSD68", RestorationFilterMode.DENOISE_SMOOTH),
    DegradationCategory("2_rain", "Ảnh mưa rơi", "Rain100L", RestorationFilterMode.DERAIN_CLEAN),
    DegradationCategory("3_haze", "Ảnh sương mù", "SOTS", RestorationFilterMode.DEHAZE_CLARITY),
    DegradationCategory("4_blur", "Ảnh bị nhòe", "GoPro", RestorationFilterMode.DEBLUR_SHARPEN),
    DegradationCategory("5_lowlight", "Ảnh thiếu sáng", "LOL", RestorationFilterMode.LOW_LIGHT_BOOST)
)

data class SampleImage(
    val categoryIndex: Int,
    val fileName: String,
    val label: String,
    val thumbnail: Bitmap
)

data class StudioUiState(
    val activeScreen: StudioScreen = StudioScreen.HOME,

    /** Danh sách các dự án ảnh đã chỉnh sửa trước đó (hiển thị ở Home kiểu CapCut). */
    val savedProjects: List<EditedProject> = emptyList(),
    val currentProjectId: String? = null,
    val currentProjectTitle: String = "",

    /** Chế độ bộ lọc khôi phục áp dụng lên bức ảnh hiện tại (KHÔNG làm đổi ảnh của người dùng). */
    val selectedFilterMode: RestorationFilterMode = RestorationFilterMode.AUTO_AI,

    /** Dữ liệu ảnh mẫu Demo / Benchmark (tách biệt hoàn toàn với bộ lọc ảnh của người dùng). */
    val selectedCategory: Int = 0,
    val samples: List<SampleImage> = emptyList(),
    val selectedSample: String? = null,
    val isGalleryImage: Boolean = true,
    val isLoadingImage: Boolean = false,

    val input: Bitmap? = null,
    val groundTruth: Bitmap? = null,
    val restored: Bitmap? = null,
    val restoration: RestorationResult? = null,
    val initialPsnr: Double? = null,
    val restoredPsnr: Double? = null,

    val executionMode: ExecutionMode = ExecutionMode.FULL_GPU,
    /** Mặc định bật chế độ Nhanh (1 mảnh 256×256) giúp tốc độ xử lý nhanh gấp ~6 lần. */
    val fastMode: Boolean = true,
    val isRestoring: Boolean = false,
    val tilesDone: Int = 0,
    val tilesTotal: Int = 0,

    val isBenchmarking: Boolean = false,
    val benchmarkMode: ExecutionMode? = null,
    val benchmarkRun: Int = 0,
    val benchmarkTotal: Int = 10,
    val benchmarkResults: List<BenchmarkResult>? = null,

    val yoloTask: YoloTaskMode = YoloTaskMode.OBJECT_DETECTION,
    val isYoloRunning: Boolean = false,
    val yoloResult: YoloComparisonResult? = null,

    val isBatchRunning: Boolean = false,
    val batchDone: Int = 0,
    val batchTotal: Int = 0,
    val batchResult: YoloBatchEvaluationResult? = null,

    val isSaving: Boolean = false
) {
    val category: DegradationCategory get() = degradationCategories[selectedCategory]
    val isBusy: Boolean get() = isRestoring || isBenchmarking || isYoloRunning || isBatchRunning
    val restoreFraction: Float?
        get() = if (tilesTotal > 0) tilesDone.toFloat() / tilesTotal else null
}

/**
 * Giữ toàn bộ trạng thái ứng dụng (Màn hình chính kiểu CapCut + Màn hình chỉnh sửa ảnh).
 * Mọi tác vụ nặng (TFLite, bộ lọc hậu kỳ, tính PSNR, đọc/lưu dự án) đều chạy ngoài luồng UI.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()
    private val engine = PromptIREngine(application)
    private val yoloEvaluator = YoloEvaluator(application)

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** Thông báo ngắn một lần (snackbar). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var loadJob: Job? = null
    private var activeTaskJob: Job? = null

    /**
     * Lưu kết quả đầu ra thuần từ mạng PromptIR cho bức ảnh hiện tại.
     * Khi người dùng đổi qua lại giữa các chế độ lọc (Khử mờ, Khử nhiễu, Tăng sáng...),
     * app áp dụng ngay bộ lọc tương ứng lên ảnh nền AI này mà không bắt chạy lại từ đầu.
     */
    @Volatile
    private var cachedNeuralBase: Bitmap? = null

    init {
        // Nạp danh sách dự án đã chỉnh sửa và danh mục ảnh mẫu trong nền, giữ màn hình ở HOME
        viewModelScope.launch(Dispatchers.IO) {
            val projects = loadSavedProjects(app)
            _uiState.update { it.copy(savedProjects = projects) }
            preloadCategorySamples(0)
            engine.warmUp(_uiState.value.executionMode)
        }
    }

    override fun onCleared() {
        engine.close()
        yoloEvaluator.close()
    }

    /** Quay về màn hình chính (Home) kiểu CapCut. */
    fun navigateToHome() {
        if (_uiState.value.isBusy) {
            cancelActiveTask()
        }
        _uiState.update { it.copy(activeScreen = StudioScreen.HOME) }
    }

    /** Hủy ngay lập tức mọi tác vụ AI đang chạy (Khôi phục, Benchmark, YOLO, Batch). */
    fun cancelActiveTask() {
        engine.cancel()
        yoloEvaluator.cancel()
        activeTaskJob?.cancel()
        activeTaskJob = null
        _uiState.update {
            it.copy(
                isRestoring = false,
                isBenchmarking = false,
                benchmarkMode = null,
                isYoloRunning = false,
                isBatchRunning = false
            )
        }
        viewModelScope.launch {
            _messages.send("Đã hủy tác vụ đang chạy")
        }
    }

    // --- Chọn chế độ lọc trên ảnh hiện tại (KHÔNG đổi ảnh của người dùng) ---

    fun selectFilterMode(mode: RestorationFilterMode) {
        val state = _uiState.value
        if (state.isBusy) return
        if (state.selectedFilterMode == mode && state.restored != null) return

        _uiState.update { it.copy(selectedFilterMode = mode) }

        // Nếu ảnh hiện tại đã được chạy AI (hoặc mở từ dự án cũ), áp dụng ngay bộ lọc mới lên ảnh hiện tại
        val baseBitmap = cachedNeuralBase ?: state.restored ?: return
        val inputBitmap = state.input ?: return

        viewModelScope.launch(Dispatchers.Default) {
            val filtered = applyRestorationModeFilter(baseBitmap, mode)
            val psnr = state.groundTruth
                ?.takeIf { it.width == filtered.width && it.height == filtered.height }
                ?.let { engine.computePsnr(filtered, it) }

            _uiState.update {
                it.copy(
                    restored = filtered,
                    restoredPsnr = psnr,
                    yoloResult = null
                )
            }

            // Tự động cập nhật lại dự án trong lịch sử "Dự án đã chỉnh sửa"
            persistCurrentProject(
                before = inputBitmap,
                neuralBase = baseBitmap,
                after = filtered,
                mode = mode,
                durationMs = state.restoration?.totalTimeMs ?: 0L,
                initialPsnr = state.initialPsnr,
                restoredPsnr = psnr
            )
        }
    }

    // --- Quản lý dự án đã chỉnh sửa (CapCut-style Projects) ---

    fun openSavedProject(project: EditedProject) {
        if (_uiState.value.isBusy) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    activeScreen = StudioScreen.EDITOR,
                    isLoadingImage = true
                )
            }
            val loaded = loadProjectBitmaps(app, project.id)
            if (loaded == null) {
                _uiState.update {
                    it.copy(
                        activeScreen = StudioScreen.HOME,
                        isLoadingImage = false,
                        savedProjects = loadSavedProjects(app)
                    )
                }
                _messages.send("Không tìm thấy dữ liệu dự án này.")
                return@launch
            }
            cachedNeuralBase = loaded.neuralBase
            val mode = RestorationFilterMode.fromKey(project.modeKey)
            _uiState.update {
                it.copy(
                    activeScreen = StudioScreen.EDITOR,
                    currentProjectId = project.id,
                    currentProjectTitle = project.title,
                    selectedFilterMode = mode,
                    input = loaded.before,
                    restored = loaded.after,
                    groundTruth = null,
                    selectedSample = null,
                    isGalleryImage = true,
                    isLoadingImage = false,
                    restoration = RestorationResult(
                        restoredBitmap = loaded.after,
                        totalTimeMs = project.durationMs,
                        avgTimePerTileMs = project.durationMs.toDouble(),
                        numTiles = 1,
                        modeUsed = it.executionMode,
                        statusMessage = "Đã mở lại từ dự án đã lưu (${project.modeTitle})",
                        isFastMode = it.fastMode
                    ),
                    initialPsnr = project.initialPsnr,
                    restoredPsnr = project.restoredPsnr,
                    yoloResult = null
                )
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteSavedProject(app, projectId)
            val updated = loadSavedProjects(app)
            _uiState.update { state ->
                state.copy(
                    savedProjects = updated,
                    currentProjectId = if (state.currentProjectId == projectId) null else state.currentProjectId
                )
            }
            _messages.send("Đã xóa dự án khỏi lịch sử")
        }
    }

    private fun persistCurrentProject(
        before: Bitmap,
        neuralBase: Bitmap,
        after: Bitmap,
        mode: RestorationFilterMode,
        durationMs: Long,
        initialPsnr: Double?,
        restoredPsnr: Double?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val saved = saveOrUpdateEditedProject(
                context = app,
                existingId = state.currentProjectId,
                customTitle = state.currentProjectTitle.takeIf { it.isNotBlank() },
                before = before,
                neuralBase = neuralBase,
                after = after,
                mode = mode,
                durationMs = durationMs,
                initialPsnr = initialPsnr,
                restoredPsnr = restoredPsnr
            )
            if (saved != null) {
                val allProjects = loadSavedProjects(app)
                _uiState.update {
                    it.copy(
                        currentProjectId = saved.id,
                        currentProjectTitle = saved.title,
                        savedProjects = allProjects
                    )
                }
            }
        }
    }

    // --- Chọn ảnh từ máy (Create Project) ---

    fun loadFromUri(uri: Uri) {
        if (_uiState.value.isBusy) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    activeScreen = StudioScreen.EDITOR,
                    isLoadingImage = true
                )
            }
            val bitmap = decodeUserImage(app, uri)
            if (bitmap == null) {
                _uiState.update { it.copy(isLoadingImage = false) }
                _messages.send("Không đọc được ảnh này. Hãy chọn một ảnh JPG hoặc PNG khác.")
                return@launch
            }
            cachedNeuralBase = null
            val defaultTitle = SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())
            _uiState.update {
                it.copy(
                    activeScreen = StudioScreen.EDITOR,
                    currentProjectId = null,
                    currentProjectTitle = defaultTitle,
                    input = bitmap,
                    groundTruth = null,
                    selectedSample = null,
                    isGalleryImage = true,
                    isLoadingImage = false,
                    restored = null,
                    restoration = null,
                    initialPsnr = null,
                    restoredPsnr = null,
                    yoloResult = null
                )
            }
        }
    }

    // --- Chọn dữ liệu ảnh mẫu Demo / Benchmark ---

    fun selectCategory(index: Int) {
        val state = _uiState.value
        if (state.isBusy) return
        _uiState.update { it.copy(selectedCategory = index, batchResult = null) }
        viewModelScope.launch(Dispatchers.IO) {
            preloadCategorySamples(index)
        }
    }

    private fun preloadCategorySamples(index: Int): List<SampleImage> {
        val key = degradationCategories[index].key
        val files = runCatching { app.assets.list("test_images/$key/degraded")?.sorted() }
            .getOrNull()
            .orEmpty()
        val samples = files.mapNotNull { file ->
            loadAssetBitmap(app, "test_images/$key/degraded/$file")?.let { bitmap ->
                SampleImage(index, file, prettySampleLabel(file), bitmap.toSquareThumbnail(160))
            }
        }
        _uiState.update { it.copy(samples = samples) }
        return samples
    }

    /** Mở một ảnh mẫu từ bộ Demo (chỉ khi người dùng chủ động bấm chọn ảnh mẫu). */
    fun selectSample(fileName: String, categoryIndex: Int = _uiState.value.selectedCategory) {
        val state = _uiState.value
        if (state.isBusy) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    activeScreen = StudioScreen.EDITOR,
                    selectedCategory = categoryIndex,
                    isLoadingImage = true
                )
            }
            val samples = if (state.selectedCategory != categoryIndex || state.samples.isEmpty()) {
                preloadCategorySamples(categoryIndex)
            } else {
                state.samples
            }
            val targetFile = fileName.ifBlank { samples.firstOrNull()?.fileName.orEmpty() }
            loadSampleInternal(categoryIndex, targetFile.ifBlank { null })
        }
    }

    private fun loadSampleInternal(categoryIndex: Int, fileName: String?) {
        val cat = degradationCategories[categoryIndex]
        val degraded = fileName?.let { loadAssetBitmap(app, "test_images/${cat.key}/degraded/$it") }
        val clean = fileName?.let { loadAssetBitmap(app, "test_images/${cat.key}/gt/$it") }
        val (input, groundTruth) = if (degraded != null && clean != null) {
            degraded to clean
        } else {
            createSyntheticPair(cat.key)
        }
        val hasPair = degraded != null && clean != null
        val psnr = engine.computePsnr(input, groundTruth)

        cachedNeuralBase = null
        val sampleLabel = fileName?.let { prettySampleLabel(it) } ?: "Mẫu"
        val projTitle = "${cat.title} - $sampleLabel"

        _uiState.update {
            it.copy(
                activeScreen = StudioScreen.EDITOR,
                currentProjectId = null,
                currentProjectTitle = projTitle,
                selectedCategory = categoryIndex,
                selectedFilterMode = cat.defaultFilterMode,
                input = input,
                groundTruth = groundTruth,
                selectedSample = if (hasPair) fileName else null,
                isGalleryImage = false,
                isLoadingImage = false,
                restored = null,
                restoration = null,
                initialPsnr = psnr,
                restoredPsnr = null,
                yoloResult = null
            )
        }
    }

    fun setExecutionMode(mode: ExecutionMode) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(executionMode = mode) }
        viewModelScope.launch(Dispatchers.IO) {
            engine.warmUp(mode)
        }
    }

    fun setFastMode(fast: Boolean) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(fastMode = fast) }
    }

    // --- Khôi phục ảnh ---

    fun restore() {
        val state = _uiState.value
        val input = state.input ?: return
        if (state.isBusy) return

        val expectedTiles = if (state.fastMode) 1 else {
            val padW = (256 - (input.width % 256)) % 256
            val padH = (256 - (input.height % 256)) % 256
            ((input.width + padW) / 256) * ((input.height + padH) / 256)
        }

        _uiState.update { it.copy(isRestoring = true, tilesDone = 0, tilesTotal = expectedTiles) }
        activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                runRestoration(
                    input = input,
                    mode = state.executionMode,
                    fastMode = state.fastMode,
                    filterMode = _uiState.value.selectedFilterMode,
                    groundTruth = state.groundTruth
                )
                val ms = _uiState.value.restoration?.totalTimeMs ?: 0L
                _messages.send("Đã khôi phục (${_uiState.value.selectedFilterMode.title}) trong ${formatDuration(ms)}")
            } catch (_: CancellationException) {
                // Đã hủy chủ động
            } catch (e: Exception) {
                _messages.send("Khôi phục thất bại: ${e.localizedMessage ?: "lỗi không xác định"}")
            } finally {
                _uiState.update { it.copy(isRestoring = false) }
            }
        }
    }

    /** Chạy PromptIR + bộ lọc chuyên biệt theo chế độ và tự động lưu vào "Dự án đã chỉnh sửa". */
    private fun runRestoration(
        input: Bitmap,
        mode: ExecutionMode,
        fastMode: Boolean,
        filterMode: RestorationFilterMode,
        groundTruth: Bitmap?
    ): Bitmap {
        val result = engine.restoreImage(
            inputBitmap = input,
            mode = mode,
            fastMode = fastMode,
            referenceHint = groundTruth
        ) { done, total, partialBitmap ->
            if (_uiState.value.isRestoring) {
                _uiState.update {
                    it.copy(
                        tilesDone = done,
                        tilesTotal = total,
                        restored = partialBitmap ?: it.restored
                    )
                }
            }
        }
        val neuralRestored = result.restoredBitmap
        cachedNeuralBase = neuralRestored

        val finalRestored = applyRestorationModeFilter(neuralRestored, filterMode)
        val psnr = groundTruth
            ?.takeIf { it.width == finalRestored.width && it.height == finalRestored.height }
            ?.let { engine.computePsnr(finalRestored, it) }

        val finalResult = result.copy(restoredBitmap = finalRestored)

        _uiState.update {
            it.copy(
                restored = finalRestored,
                restoration = finalResult,
                restoredPsnr = psnr,
                yoloResult = null
            )
        }

        // Tự động lưu vào lịch sử "Dự án đã chỉnh sửa" kiểu CapCut
        persistCurrentProject(
            before = input,
            neuralBase = neuralRestored,
            after = finalRestored,
            mode = filterMode,
            durationMs = finalResult.totalTimeMs,
            initialPsnr = _uiState.value.initialPsnr,
            restoredPsnr = psnr
        )

        return finalRestored
    }

    // --- Benchmark phần cứng ---

    fun runBenchmark() {
        val state = _uiState.value
        val input = state.input ?: createSyntheticPair(state.category.key).first
        if (state.isBusy) return

        _uiState.update {
            it.copy(isBenchmarking = true, benchmarkResults = null, benchmarkRun = 0, benchmarkMode = null)
        }
        activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val cropW = minOf(256, input.width)
                val cropH = minOf(256, input.height)
                val patch = Bitmap.createBitmap(
                    input,
                    (input.width - cropW) / 2,
                    (input.height - cropH) / 2,
                    cropW,
                    cropH
                )
                val results = engine.runBenchmark(patch) { modeName, run, total ->
                    if (_uiState.value.isBenchmarking) {
                        _uiState.update {
                            it.copy(
                                benchmarkMode = ExecutionMode.valueOf(modeName),
                                benchmarkRun = run,
                                benchmarkTotal = total
                            )
                        }
                    }
                }
                _uiState.update { it.copy(benchmarkResults = results) }
            } catch (_: CancellationException) {
                // Đã hủy chủ động
            } catch (e: Exception) {
                _messages.send("Đo tốc độ thất bại: ${e.localizedMessage ?: "lỗi không xác định"}")
            } finally {
                _uiState.update { it.copy(isBenchmarking = false, benchmarkMode = null) }
            }
        }
    }

    // --- Đánh giá YOLOv8 ---

    fun setYoloTask(task: YoloTaskMode) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(yoloTask = task, yoloResult = null) }
    }

    fun runYolo() {
        val state = _uiState.value
        val input = state.input ?: return
        if (state.isBusy) return

        _uiState.update { it.copy(isYoloRunning = true) }
        activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val restored = state.restored ?: run {
                    _uiState.update { it.copy(isRestoring = true, tilesDone = 0, tilesTotal = 1) }
                    try {
                        runRestoration(
                            input = input,
                            mode = state.executionMode,
                            fastMode = state.fastMode,
                            filterMode = state.selectedFilterMode,
                            groundTruth = state.groundTruth
                        )
                    } finally {
                        _uiState.update { it.copy(isRestoring = false) }
                    }
                }
                val result = when (state.yoloTask) {
                    YoloTaskMode.OBJECT_DETECTION ->
                        yoloEvaluator.evaluateObjectDetection(input, restored, state.groundTruth)
                    YoloTaskMode.INSTANCE_SEGMENTATION ->
                        yoloEvaluator.evaluateInstanceSegmentation(input, restored, state.groundTruth)
                }
                _uiState.update { it.copy(yoloResult = result) }
            } catch (_: CancellationException) {
                // Đã hủy chủ động
            } catch (e: Exception) {
                _messages.send("YOLO gặp lỗi: ${e.localizedMessage ?: "lỗi không xác định"}")
            } finally {
                _uiState.update { it.copy(isYoloRunning = false) }
            }
        }
    }

    fun runBatchEvaluation() {
        val state = _uiState.value
        if (state.isBusy) return

        _uiState.update { it.copy(isBatchRunning = true, batchDone = 0, batchTotal = 0, batchResult = null) }
        activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val key = state.category.key
                val files = runCatching { app.assets.list("test_images/$key/degraded")?.sorted() }
                    .getOrNull()
                    .orEmpty()
                val pairs = files.mapNotNull { file ->
                    val degraded = loadAssetBitmap(app, "test_images/$key/degraded/$file")
                    val clean = loadAssetBitmap(app, "test_images/$key/gt/$file")
                    if (degraded != null && clean != null) degraded to clean else null
                }.ifEmpty { List(3) { createSyntheticPair(key) } }

                _uiState.update { it.copy(batchTotal = pairs.size) }

                val result = yoloEvaluator.evaluateBatch(
                    categoryName = "${state.category.title} (${state.category.dataset})",
                    imagePairs = pairs,
                    engine = engine,
                    executionMode = state.executionMode,
                    taskMode = state.yoloTask
                ) { done, total ->
                    if (_uiState.value.isBatchRunning) {
                        _uiState.update { it.copy(batchDone = done, batchTotal = total) }
                    }
                }
                _uiState.update { it.copy(batchResult = result) }
            } catch (_: CancellationException) {
                // Đã hủy chủ động
            } catch (e: Exception) {
                _messages.send("Đánh giá cả bộ thất bại: ${e.localizedMessage ?: "lỗi không xác định"}")
            } finally {
                _uiState.update { it.copy(isBatchRunning = false) }
            }
        }
    }

    // --- Lưu ảnh ra thư viện máy ---

    fun saveRestored() {
        val state = _uiState.value
        val restored = state.restored ?: return
        if (state.isSaving) return

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val name = "promptir_${state.selectedFilterMode.key}_${System.currentTimeMillis()}"
            val ok = saveBitmapToGallery(app, restored, name)
            _uiState.update { it.copy(isSaving = false) }
            _messages.send(
                if (ok) "Đã xuất ảnh vào Thư viện (Pictures/PromptIR)"
                else "Chưa lưu được ảnh. Kiểm tra dung lượng trống rồi thử lại."
            )
        }
    }
}

fun formatDuration(ms: Long): String =
    if (ms < 1000) "$ms ms" else String.format(Locale.US, "%.1f giây", ms / 1000.0)
