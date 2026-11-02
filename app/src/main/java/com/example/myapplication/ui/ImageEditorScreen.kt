package com.example.myapplication.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.example.myapplication.EditedProject
import com.example.myapplication.ExecutionMode
import com.example.myapplication.R
import com.example.myapplication.RestorationFilterMode
import com.example.myapplication.SampleImage
import com.example.myapplication.StudioScreen
import com.example.myapplication.StudioUiState
import com.example.myapplication.YoloTaskMode
import com.example.myapplication.degradationCategories
import com.example.myapplication.formatDuration
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.ui.theme.StudioAccent
import com.example.myapplication.ui.theme.StudioBorder
import com.example.myapplication.ui.theme.StudioCanvas
import com.example.myapplication.ui.theme.StudioSuccess
import com.example.myapplication.ui.theme.StudioSurface
import com.example.myapplication.ui.theme.StudioSurfaceElevated
import com.example.myapplication.ui.theme.StudioTextPrimary
import com.example.myapplication.ui.theme.StudioTextSecondary

/**
 * Điều phối giữa:
 * - Màn hình chính kiểu CapCut ([StudioHomeScreen]): Nút "+ Tạo dự án mới" & danh sách "Dự án đã chỉnh sửa"
 * - Màn hình chỉnh sửa ảnh ([ImageEditorScreen]):
 *   + Giữ nguyên ảnh của người dùng khi chọn các chế độ lọc (Tự động AI, Khử mờ, Khử nhiễu, Tăng sáng, Khử sương, Khử mưa)
 *   + Vùng nhìn ảnh (Image Viewport) chiếm ~68% chiều cao màn hình (Canvas-First)
 *   + Before/After Split Slider (nút tròn 48dp, đồng bộ Pinch-to-Zoom 1x-5x)
 *   + AI Scanning Line khi đang xử lý
 */
@Composable
fun ImageEditorScreen(
    state: StudioUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateHome: () -> Unit,
    onOpenSavedProject: (EditedProject) -> Unit,
    onDeleteProject: (String) -> Unit,
    onSelectFilterMode: (RestorationFilterMode) -> Unit,
    onSelectCategory: (Int) -> Unit,
    onSelectSample: (Int, String) -> Unit,
    onPickFromGallery: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onSelectExecutionMode: (ExecutionMode) -> Unit,
    onSelectFastMode: (Boolean) -> Unit,
    onRunBenchmark: () -> Unit,
    onSelectYoloTask: (YoloTaskMode) -> Unit,
    onRunYolo: () -> Unit,
    onRunBatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showSampleSheet by rememberSaveable { mutableStateOf(false) }
    var showFullRestored by rememberSaveable { mutableStateOf(false) }

    // Khi đang ở màn hình Editor, bấm nút Back hệ thống sẽ quay về trang chủ dự án (như CapCut)
    BackHandler(enabled = state.activeScreen == StudioScreen.EDITOR) {
        onNavigateHome()
    }

    if (state.activeScreen == StudioScreen.HOME) {
        StudioHomeScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onCreateNewProject = {
                showFullRestored = false
                onPickFromGallery()
            },
            onOpenSavedProject = { project ->
                showFullRestored = false
                onOpenSavedProject(project)
            },
            onDeleteProject = onDeleteProject,
            onSelectDemoCategory = onSelectCategory,
            onPickDemoSample = { catIdx, fileName ->
                showFullRestored = false
                onSelectSample(catIdx, fileName)
            },
            onOpenDiagnostics = { showDiagnostics = true },
            modifier = modifier
        )
    } else {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .background(StudioCanvas)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            containerColor = StudioCanvas,
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = StudioSurfaceElevated,
                        contentColor = StudioTextPrimary,
                        shape = PillShape
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // --- 1. TOP STUDIO BAR (Nút quay lại Home + Tên dự án + Tốc độ + Lưu ảnh) ---
                StudioTopBar(
                    state = state,
                    onBackToHome = onNavigateHome,
                    onToggleFastMode = { onSelectFastMode(!state.fastMode) },
                    onOpenGallery = onPickFromGallery,
                    onOpenDiagnostics = { showDiagnostics = true },
                    onSave = onSave
                )

                // --- 2. IMAGE VIEWPORT (Chiếm ~68% chiều cao màn hình - Canvas-First) ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    val processingLabel = when {
                        state.isRestoring && state.fastMode ->
                            "Đang lọc ${state.selectedFilterMode.title} (${state.executionMode.shortLabel})..."
                        state.isRestoring && state.tilesTotal > 0 ->
                            "Đang khôi phục mảnh ${state.tilesDone + 1}/${state.tilesTotal} (${state.executionMode.shortLabel})..."
                        state.isRestoring ->
                            "Đang xử lý ảnh (${state.executionMode.shortLabel})..."
                        else -> ""
                    }

                    BeforeAfterViewer(
                        before = state.input,
                        after = state.restored,
                        showFullRestored = showFullRestored,
                        isProcessing = state.isRestoring,
                        processingLabel = processingLabel,
                        processingFraction = state.restoreFraction,
                        onCancelProcessing = onCancel,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (state.isLoadingImage) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = StudioAccent,
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp
                                )
                                Text(
                                    text = "Đang nạp ảnh...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StudioTextSecondary
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Chưa có ảnh nào được chọn",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioTextPrimary
                                )
                                StudioSecondaryButton(
                                    text = "Chọn ảnh từ máy",
                                    iconRes = R.drawable.ic_studio_photo_library,
                                    onClick = onPickFromGallery
                                )
                            }
                        }
                    }

                    // Thanh HUD mỏng ở góc trên ảnh: Chế độ lọc + PSNR + Nút Xem ảnh đầy đủ (chỉ hiện sau khi chạy AI)
                    ViewportTopHud(
                        state = state,
                        showFullRestored = showFullRestored,
                        onToggleFullRestored = { showFullRestored = !showFullRestored },
                        onOpenDiagnostics = { showDiagnostics = true },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }

                // --- 3. BOTTOM CONTROL SHEET (1/3 dưới màn hình - Thao tác 1 ngón cái) ---
                BottomStudioDock(
                    selectedFilterMode = state.selectedFilterMode,
                    isGalleryImage = state.isGalleryImage,
                    isBusy = state.isBusy,
                    isRestoring = state.isRestoring,
                     isSaving = state.isSaving,
                    fastMode = state.fastMode,
                    hasRestored = state.restored != null,
                    restoreFraction = state.restoreFraction,
                    tilesDone = state.tilesDone,
                    tilesTotal = state.tilesTotal,
                    onSelectFilterMode = { mode ->
                        onSelectFilterMode(mode)
                    },
                    onPickFromGallery = {
                        showFullRestored = false
                        onPickFromGallery()
                    },
                    onOpenDemoSamples = { showSampleSheet = true },
                    onRestore = {
                        showFullRestored = false
                        onRestore()
                    },
                    onSave = onSave,
                    onCancel = onCancel
                )
            }
        }
    }

    if (showSampleSheet) {
        SampleDatasetPickerSheet(
            categories = degradationCategories,
            selectedCategory = state.selectedCategory,
            samples = state.samples,
            onSelectCategory = onSelectCategory,
            onSelectSample = { catIdx, fileName ->
                showSampleSheet = false
                showFullRestored = false
                onSelectSample(catIdx, fileName)
            },
            onDismiss = { showSampleSheet = false }
        )
    }

    if (showDiagnostics) {
        DiagnosticsSheet(
            state = state,
            onDismiss = { showDiagnostics = false },
            onSelectExecutionMode = onSelectExecutionMode,
            onSelectFastMode = onSelectFastMode,
            onRunBenchmark = onRunBenchmark,
            onSelectCategory = onSelectCategory,
            onSelectYoloTask = onSelectYoloTask,
            onRunYolo = onRunYolo,
            onRunBatch = onRunBatch,
            onCancel = onCancel
        )
    }
}

/**
 * Top Bar của màn hình chỉnh sửa:
 * Nút Quay lại Home + Tên dự án + Chip Tốc độ (Nhanh/HD) + Đổi ảnh + Phân tích + Lưu vào thư viện
 */
@Composable
private fun StudioTopBar(
    state: StudioUiState,
    onBackToHome: () -> Unit,
    onToggleFastMode: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Cụm trái: Nút Back về Home + Tên dự án + Huy hiệu Nhanh/HD (không bao giờ bị ép chữ)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
        ) {
            StudioIconButton(
                iconRes = R.drawable.ic_studio_back,
                contentDescription = "Quay lại danh sách dự án",
                onClick = onBackToHome
            )

            Text(
                text = state.currentProjectTitle.ifBlank { "Dự án mới" },
                style = MaterialTheme.typography.titleMedium,
                color = StudioTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (state.fastMode) StudioSuccess.copy(alpha = 0.15f) else StudioSurfaceElevated)
                    .border(
                        1.dp,
                        if (state.fastMode) StudioSuccess.copy(alpha = 0.5f) else StudioBorder,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = !state.isBusy, role = Role.Button, onClick = onToggleFastMode)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.fastMode) "⚡ Nhanh" else "HD Đa mảnh",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.fastMode) StudioSuccess else StudioTextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Cụm phải: Đổi ảnh khác từ máy, Mở bảng YOLO & Benchmark, Xuất ảnh ra Gallery
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            StudioIconButton(
                iconRes = R.drawable.ic_studio_photo_library,
                contentDescription = "Đổi ảnh khác từ máy",
                enabled = !state.isBusy,
                onClick = onOpenGallery
            )

            StudioIconButton(
                iconRes = R.drawable.ic_studio_insights,
                contentDescription = "Mở Benchmark và Đánh giá YOLOv8",
                onClick = onOpenDiagnostics
            )

            val canSave = state.restored != null && !state.isBusy && !state.isSaving
            StudioIconButton(
                iconRes = R.drawable.ic_studio_download,
                contentDescription = "Xuất ảnh ra Thư viện máy",
                enabled = canSave,
                tint = if (canSave) StudioSuccess else StudioTextSecondary,
                onClick = onSave
            )
        }
    }
}

/**
 * Thanh thông số nổi gọn gàng phía trên ảnh:
 * CHỈ hiển thị sau khi đã chạy AI xong (`state.restored != null && !state.isRestoring`):
 * - Cụm trái: Chế độ lọc đã áp dụng + Thời gian xử lý (+ độ tăng PSNR nếu là ảnh mẫu)
 * - Cụm phải: Nút "Xem ảnh đầy đủ" <-> "So sánh Trước/Sau"
 */
@Composable
private fun ViewportTopHud(
    state: StudioUiState,
    showFullRestored: Boolean,
    onToggleFullRestored: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.restored == null || state.isRestoring) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val pre = state.initialPsnr
        val post = state.restoredPsnr
        val res = state.restoration

        OverlayChip(onClick = onOpenDiagnostics) {
            Text(
                text = state.selectedFilterMode.title,
                style = MaterialTheme.typography.labelMedium,
                color = StudioSuccess
            )
            if (pre != null && post != null) {
                DeltaBadge(value = post - pre, decimals = 1, suffix = " dB")
            }
            if (res != null) {
                Text(
                    text = "• ${formatDuration(res.totalTimeMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextSecondary
                )
            }
        }

        OverlayChip(
            selected = showFullRestored,
            onClick = onToggleFullRestored
        ) {
            Text(
                text = if (showFullRestored) "So sánh Trước/Sau" else "Xem ảnh đầy đủ",
                style = MaterialTheme.typography.labelMedium,
                color = StudioTextPrimary
            )
        }
    }
}

/**
 * Bảng điều khiển cố định ở đáy màn hình (Bottom Control Sheet) bo góc trên 24dp:
 * - Dải cuộn ngang chọn Chế độ lọc làm rõ ảnh (Tự động AI, Khử mờ, Khử nhiễu, Tăng sáng, Khử sương, Khử mưa)
 *   -> Khi chọn chỉ đổi bộ lọc áp dụng lên bức ảnh đang mở, tuyệt đối KHÔNG tự đổi sang ảnh mẫu khác!
 * - Cụm nút hành động chính ở tầm ngón cái:
 *   + Khi chưa chạy AI: Nút "Làm rõ ảnh ngay"
 *   + Sau khi chạy AI xong: Nút "Lưu ảnh về máy" + nút phụ "Chạy lại"
 */
@Composable
private fun BottomStudioDock(
    selectedFilterMode: RestorationFilterMode,
    isGalleryImage: Boolean,
    isBusy: Boolean,
    isRestoring: Boolean,
    isSaving: Boolean,
    fastMode: Boolean,
    hasRestored: Boolean,
    restoreFraction: Float?,
    tilesDone: Int,
    tilesTotal: Int,
    onSelectFilterMode: (RestorationFilterMode) -> Unit,
    onPickFromGallery: () -> Unit,
    onOpenDemoSamples: () -> Unit,
    onRestore: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val filterModes = RestorationFilterMode.entries

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StudioSurface,
        shape = SheetShape,
        border = StudioBorderStroke
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Tiêu đề hướng dẫn + nút chọn ảnh khác / ảnh mẫu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "Chế độ lọc & làm rõ ảnh",
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (hasRestored) {
                            "Chạm chế độ để đổi kiểu lọc ngay"
                        } else {
                            "Chọn chế độ lọc cho ảnh hiện tại"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioSurfaceElevated)
                            .border(1.dp, StudioBorder, RoundedCornerShape(8.dp))
                            .clickable(enabled = !isBusy, role = Role.Button, onClick = onPickFromGallery)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Đổi ảnh",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextPrimary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isGalleryImage) StudioAccent.copy(alpha = 0.18f) else StudioSurfaceElevated)
                            .border(
                                1.dp,
                                if (!isGalleryImage) StudioAccent else StudioBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !isBusy, role = Role.Button, onClick = onOpenDemoSamples)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Ảnh mẫu",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (!isGalleryImage) StudioAccent else StudioTextSecondary
                        )
                    }
                }
            }

            // 2. Dải cuộn ngang chọn chế độ lọc khôi phục (Degradation / Restoration Mode Selector)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filterModes, key = { it.key }) { mode ->
                    DegradationPill(
                        title = mode.title,
                        caption = mode.subtitle,
                        selected = mode == selectedFilterMode,
                        enabled = !isBusy,
                        onClick = { onSelectFilterMode(mode) }
                    )
                }
            }

            // 3. Hàng nút hành động chính ở tầm ngón cái
            if (hasRestored && !isRestoring) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StudioSecondaryButton(
                        text = "Chạy lại",
                        iconRes = R.drawable.ic_studio_auto_fix,
                        enabled = !isBusy,
                        onClick = onRestore,
                        modifier = Modifier.height(52.dp)
                    )

                    StudioPrimaryButton(
                        text = if (isSaving) "Đang lưu ảnh..." else "Lưu ảnh về máy",
                        onClick = onSave,
                        enabled = !isBusy && !isSaving,
                        iconRes = R.drawable.ic_studio_download,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                val ctaText = when {
                    isRestoring && !fastMode && tilesTotal > 0 ->
                        "Đang xử lý mảnh ${tilesDone + 1}/$tilesTotal..."
                    isRestoring ->
                        "Đang lọc (${selectedFilterMode.title})..."
                    else ->
                        "Làm rõ ảnh ngay (${selectedFilterMode.title})"
                }

                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    StudioPrimaryButton(
                        text = ctaText,
                        onClick = onRestore,
                        enabled = !isBusy,
                        iconRes = R.drawable.ic_studio_auto_fix,
                        inProgress = isRestoring,
                        progress = restoreFraction,
                        onCancel = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// --- PREVIEW DARK MODE TRỰC QUAN TRONG ANDROID STUDIO ---
@Preview(showBackground = true, backgroundColor = 0xFF0F0F12, widthDp = 390, heightDp = 844)
@Composable
private fun ImageEditorScreenPreview() {
    val mockBefore = remember {
        createBitmap(480, 640).apply {
            val c = AndroidCanvas(this)
            c.drawColor(android.graphics.Color.rgb(55, 58, 68))
            val p = AndroidPaint().apply {
                color = android.graphics.Color.rgb(180, 185, 195)
                isAntiAlias = true
            }
            c.drawCircle(240f, 280f, 110f, p)
        }
    }
    val mockAfter = remember {
        createBitmap(480, 640).apply {
            val c = AndroidCanvas(this)
            c.drawColor(android.graphics.Color.rgb(28, 36, 56))
            val p = AndroidPaint().apply {
                color = android.graphics.Color.rgb(99, 102, 241)
                isAntiAlias = true
            }
            c.drawCircle(240f, 280f, 110f, p)
        }
    }

    val previewState = StudioUiState(
        activeScreen = StudioScreen.EDITOR,
        currentProjectTitle = "0719_Portrait",
        selectedFilterMode = RestorationFilterMode.DEBLUR_SHARPEN,
        samples = listOf(
            SampleImage(0, "sample_1_bus.png", "Bus", mockBefore),
            SampleImage(0, "sample_2_zidane.png", "Zidane", mockAfter)
        ),
        isLoadingImage = false,
        input = mockBefore,
        groundTruth = mockAfter,
        restored = mockAfter,
        initialPsnr = 20.49,
        restoredPsnr = 28.84
    )

    MyApplicationTheme {
        ImageEditorScreen(
            state = previewState,
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateHome = {},
            onOpenSavedProject = {},
            onDeleteProject = {},
            onSelectFilterMode = {},
            onSelectCategory = {},
            onSelectSample = { _, _ -> },
            onPickFromGallery = {},
            onRestore = {},
            onCancel = {},
            onSave = {},
            onSelectExecutionMode = {},
            onSelectFastMode = {},
            onRunBenchmark = {},
            onSelectYoloTask = {},
            onRunYolo = {},
            onRunBatch = {}
        )
    }
}
