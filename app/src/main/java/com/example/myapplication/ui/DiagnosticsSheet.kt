package com.example.myapplication.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.myapplication.BenchmarkResult
import com.example.myapplication.ExecutionMode
import com.example.myapplication.R
import com.example.myapplication.StudioUiState
import com.example.myapplication.YoloBatchEvaluationResult
import com.example.myapplication.YoloComparisonResult
import com.example.myapplication.YoloTaskMode
import com.example.myapplication.degradationCategories
import com.example.myapplication.formatDuration
import com.example.myapplication.ui.theme.StudioAccent
import com.example.myapplication.ui.theme.StudioBorder
import com.example.myapplication.ui.theme.StudioCanvas
import com.example.myapplication.ui.theme.StudioSuccess
import com.example.myapplication.ui.theme.StudioSurface
import com.example.myapplication.ui.theme.StudioSurfaceElevated
import com.example.myapplication.ui.theme.StudioTextPrimary
import com.example.myapplication.ui.theme.StudioTextSecondary

/**
 * Bottom Sheet Phân Tích & Cấu Hình Chuyên Sâu:
 * - Chọn chế độ xử lý (Nhanh 1 mảnh vs Chi tiết Đa mảnh)
 * - Chọn phần cứng thực thi (Full GPU, GPU + CPU, Full CPU)
 * - Đo Benchmark 3 chế độ
 * - Đánh giá YOLOv8 (Object Detection, Instance Segmentation & Batch mAP)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    state: StudioUiState,
    onDismiss: () -> Unit,
    onSelectExecutionMode: (ExecutionMode) -> Unit,
    onSelectFastMode: (Boolean) -> Unit,
    onRunBenchmark: () -> Unit,
    onSelectCategory: (Int) -> Unit,
    onSelectYoloTask: (YoloTaskMode) -> Unit,
    onRunYolo: () -> Unit,
    onRunBatch: () -> Unit,
    onCancel: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by remember { mutableIntStateOf(0) } // 0: Phần cứng & Benchmark, 1: YOLOv8

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioSurface,
        contentColor = StudioTextPrimary,
        shape = SheetShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            StudioSegmentedControl(
                options = listOf("Phần cứng & Tốc độ", "Đánh giá YOLOv8"),
                selectedIndex = activeTab,
                onSelect = { activeTab = it }
            )

            if (activeTab == 0) {
                HardwareAndBenchmarkContent(
                    state = state,
                    onSelectExecutionMode = onSelectExecutionMode,
                    onSelectFastMode = onSelectFastMode,
                    onRunBenchmark = onRunBenchmark,
                    onCancel = onCancel
                )
            } else {
                YoloEvaluationContent(
                    state = state,
                    onSelectCategory = onSelectCategory,
                    onSelectYoloTask = onSelectYoloTask,
                    onRunYolo = onRunYolo,
                    onRunBatch = onRunBatch,
                    onCancel = onCancel
                )
            }
        }
    }
}

@Composable
private fun HardwareAndBenchmarkContent(
    state: StudioUiState,
    onSelectExecutionMode: (ExecutionMode) -> Unit,
    onSelectFastMode: (Boolean) -> Unit,
    onRunBenchmark: () -> Unit,
    onCancel: () -> Unit
) {
    val modes = ExecutionMode.entries

    // 1. Chọn chế độ phân giải / tốc độ
    SectionHeader(
        title = "Chế độ khôi phục ảnh",
        subtitle = if (state.fastMode) {
            "Nhanh (1 mảnh 256×256): Xử lý toàn ảnh trong 1 lượt duy nhất, hoàn tất tức thì"
        } else {
            "Chi tiết (Đa mảnh): Chia ảnh gốc thành nhiều mảnh 256×256 (chậm hơn nhưng giữ độ phân giải gốc)"
        }
    )

    StudioSegmentedControl(
        options = listOf("Nhanh (1 mảnh)", "Chi tiết (Đa mảnh)"),
        selectedIndex = if (state.fastMode) 0 else 1,
        onSelect = { onSelectFastMode(it == 0) },
        enabled = !state.isBusy
    )

    // 2. Chọn chế độ phần cứng
    SectionHeader(
        title = "Bộ gia tốc phần cứng",
        subtitle = state.executionMode.description
    )

    StudioSegmentedControl(
        options = modes.map { it.shortLabel },
        selectedIndex = modes.indexOf(state.executionMode),
        onSelect = { onSelectExecutionMode(modes[it]) },
        enabled = !state.isBusy
    )

    // 3. Chỉ số khôi phục hiện tại (nếu có)
    state.restoration?.let { res ->
        Surface(
            color = StudioSurfaceElevated,
            shape = PillShape,
            border = StudioBorderStroke
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Kết quả lần khôi phục gần nhất",
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioTextPrimary
                )
                MetricRow("Chế độ lọc", state.selectedFilterMode.title)
                MetricRow("Tổng thời gian", formatDuration(res.totalTimeMs))
                MetricRow("Số mảnh 256×256", "${res.numTiles} mảnh (${res.avgTimePerTileMs.format(0)} ms/mảnh)")
                if (state.initialPsnr != null && state.restoredPsnr != null) {
                    ComparisonRow(
                        label = "Chỉ số PSNR",
                        before = state.initialPsnr,
                        after = state.restoredPsnr,
                        decimals = 2,
                        unit = " dB"
                    )
                }
                Text(
                    text = res.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary
                )
            }
        }
    }

    StudioDivider()

    // 4. Benchmark 3 chế độ
    SectionHeader(
        title = "Benchmark tốc độ suy luận",
        subtitle = "Đo tốc độ xử lý trên patch 256×256 trung tâm (2 lần warm-up + 10 lần đo chính thức)"
    )

    val benchmarkFraction = if (state.isBenchmarking && state.benchmarkTotal > 0) {
        state.benchmarkRun.toFloat() / state.benchmarkTotal
    } else null

    val benchmarkBtnLabel = if (state.isBenchmarking) {
        val m = state.benchmarkMode?.shortLabel ?: "..."
        "Đang đo $m (${state.benchmarkRun}/${state.benchmarkTotal})"
    } else {
        "Chạy Benchmark 3 chế độ"
    }

    StudioPrimaryButton(
        text = benchmarkBtnLabel,
        onClick = onRunBenchmark,
        enabled = !state.isBusy,
        iconRes = R.drawable.ic_studio_chip,
        inProgress = state.isBenchmarking,
        progress = benchmarkFraction,
        onCancel = onCancel,
        modifier = Modifier.fillMaxWidth()
    )

    state.benchmarkResults?.let { list ->
        BenchmarkResultList(results = list)
    }
}

@Composable
private fun BenchmarkResultList(results: List<BenchmarkResult>) {
    val cpuAvg = results.find { it.mode == ExecutionMode.FULL_CPU }?.avgTimeMs ?: 1.0

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        results.forEach { item ->
            val speedup = if (item.avgTimeMs > 0) cpuAvg / item.avgTimeMs else 1.0
            val badgeText = when {
                item.mode == ExecutionMode.FULL_CPU -> "Chuẩn đối chiếu (CPU)"
                item.isGpuSuccess -> "${speedup.format(2)}× vs CPU"
                else -> "Fallback sang CPU"
            }
            val badgeColor = if (item.isGpuSuccess) StudioSuccess else StudioAccent

            Surface(
                color = StudioSurfaceElevated,
                shape = PillShape,
                border = StudioBorderStroke
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.mode.shortLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeColor.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor
                            )
                        }
                    }

                    MetricRow(
                        label = "Trung bình",
                        value = "${item.avgTimeMs.format(1)} ms / patch"
                    )
                    MetricRow(
                        label = "Min / Max",
                        value = "${item.minTimeMs} ms / ${item.maxTimeMs} ms"
                    )
                    Text(
                        text = item.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun YoloEvaluationContent(
    state: StudioUiState,
    onSelectCategory: (Int) -> Unit,
    onSelectYoloTask: (YoloTaskMode) -> Unit,
    onRunYolo: () -> Unit,
    onRunBatch: () -> Unit,
    onCancel: () -> Unit
) {
    val tasks = YoloTaskMode.entries

    SectionHeader(
        title = "Đánh giá thị giác máy tính (YOLOv8)",
        subtitle = "Kiểm tra khả năng nhận diện vật thể trước và sau khi ảnh được làm rõ bằng AI"
    )

    StudioSegmentedControl(
        options = listOf("Object Detection", "Segmentation"),
        selectedIndex = tasks.indexOf(state.yoloTask),
        onSelect = { onSelectYoloTask(tasks[it]) },
        enabled = !state.isBusy
    )

    // 1. Chạy YOLOv8 trên bức ảnh đang mở (nếu có)
    if (state.input != null) {
        StudioPrimaryButton(
            text = if (state.isYoloRunning) "Đang chạy YOLOv8 trên ảnh này..." else "Nhận diện trên bức ảnh đang mở",
            onClick = onRunYolo,
            enabled = !state.isBusy,
            inProgress = state.isYoloRunning,
            progress = 0.5f,
            onCancel = onCancel,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Kết quả YOLO trên ảnh đơn
    state.yoloResult?.let { yRes ->
        YoloSingleResultCard(result = yRes)
    }

    StudioDivider()

    // 2. Đánh giá Batch mAP trên 5 bộ dữ liệu chuẩn (có thể chọn trực tiếp bộ mẫu ngay tại đây)
    SectionHeader(
        title = "Đo mAP trên bộ dữ liệu chuẩn (Batch Test)",
        subtitle = "Chọn 1 trong 5 bộ ảnh chuẩn có sẵn Ground Truth để đo độ cải thiện mAP"
    )

    LazyRow(
        contentPadding = PaddingValues(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(degradationCategories) { index, item ->
            DegradationPill(
                title = item.title,
                caption = item.dataset,
                selected = index == state.selectedCategory,
                enabled = !state.isBusy,
                onClick = { onSelectCategory(index) }
            )
        }
    }

    val batchProgress = if (state.isBatchRunning && state.batchTotal > 0) {
        state.batchDone.toFloat() / state.batchTotal
    } else 0f

    StudioPrimaryButton(
        text = if (state.isBatchRunning) {
            "Đang đo Batch ${state.category.title} (${state.batchDone}/${state.batchTotal})..."
        } else {
            "Đo mAP cả bộ: ${state.category.title} (${state.category.dataset})"
        },
        onClick = onRunBatch,
        enabled = !state.isBusy,
        inProgress = state.isBatchRunning,
        progress = batchProgress,
        onCancel = onCancel,
        modifier = Modifier.fillMaxWidth()
    )

    // Kết quả Batch Evaluation trên cả tập test
    state.batchResult?.let { bRes ->
        YoloBatchResultCard(result = bRes)
    }
}

@Composable
private fun YoloSingleResultCard(result: YoloComparisonResult) {
    var imageTab by remember(result.hasGroundTruth) { mutableIntStateOf(1) }

    val tabOptions = if (result.hasGroundTruth) {
        listOf(
            "Trước (${result.numDegradedDetections})",
            "Sau (${result.numRestoredDetections})",
            "Ảnh chuẩn (${result.numGtDetections})"
        )
    } else {
        listOf(
            "Trước khi lọc (${result.numDegradedDetections})",
            "Sau khi lọc (${result.numRestoredDetections})"
        )
    }

    Surface(
        color = StudioSurfaceElevated,
        shape = PillShape,
        border = StudioBorderStroke
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Kết quả nhận diện YOLOv8 trên ảnh hiện tại",
                style = MaterialTheme.typography.titleSmall,
                color = StudioTextPrimary
            )

            StudioSegmentedControl(
                options = tabOptions,
                selectedIndex = imageTab.coerceIn(0, tabOptions.lastIndex),
                onSelect = { imageTab = it }
            )

            val activeBitmap = when (imageTab) {
                0 -> result.degradedAnnotatedBitmap
                1 -> result.restoredAnnotatedBitmap
                else -> result.gtAnnotatedBitmap
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(ViewportShape)
                    .background(StudioCanvas)
                    .border(1.dp, StudioBorder, ViewportShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = activeBitmap.asImageBitmap(),
                    contentDescription = "Ảnh kết quả YOLOv8",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            ComparisonRow(
                label = "Số vật thể phát hiện",
                before = result.numDegradedDetections.toDouble(),
                after = result.numRestoredDetections.toDouble(),
                decimals = 0,
                unit = " vật thể"
            )

            ComparisonRow(
                label = "Độ tự tin TB",
                before = result.avgDegradedConf * 100,
                after = result.avgRestoredConf * 100,
                decimals = 1,
                unit = "%"
            )

            if (result.hasGroundTruth) {
                ComparisonRow(
                    label = "mAP@0.50 (so với GT)",
                    before = result.degradedMap50 * 100,
                    after = result.restoredMap50 * 100,
                    decimals = 1,
                    unit = "%"
                )
                ComparisonRow(
                    label = "mAP@0.50:0.95 (so với GT)",
                    before = result.degradedMap50_95 * 100,
                    after = result.restoredMap50_95 * 100,
                    decimals = 1,
                    unit = "%"
                )
            }

            MetricRow(
                label = "Thời gian suy luận",
                value = formatDuration(result.inferenceTimeMs)
            )
            Text(
                text = "Đối tượng nhận diện: ${result.detectedClassSummary}",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary
            )
        }
    }
}

@Composable
private fun YoloBatchResultCard(result: YoloBatchEvaluationResult) {
    Surface(
        color = StudioSurfaceElevated,
        shape = PillShape,
        border = StudioBorderStroke
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Báo cáo Batch: ${result.categoryName}",
                style = MaterialTheme.typography.titleSmall,
                color = StudioTextPrimary
            )
            MetricRow("Tổng số ảnh", "${result.totalImages} ảnh (${formatDuration(result.totalTimeMs)})")
            ComparisonRow(
                label = "mAP@0.50 TB",
                before = result.avgDegradedMap50 * 100,
                after = result.avgRestoredMap50 * 100,
                decimals = 1,
                unit = "%"
            )
            ComparisonRow(
                label = "mAP@0.50:0.95 TB",
                before = result.avgDegradedMap50_95 * 100,
                after = result.avgRestoredMap50_95 * 100,
                decimals = 1,
                unit = "%"
            )
            ComparisonRow(
                label = "Độ tự tin TB",
                before = result.avgDegradedConf * 100,
                after = result.avgRestoredConf * 100,
                decimals = 1,
                unit = "%"
            )
        }
    }
}
