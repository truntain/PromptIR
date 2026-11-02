package com.example.myapplication.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.example.myapplication.DegradationCategory
import com.example.myapplication.EditedProject
import com.example.myapplication.R
import com.example.myapplication.RestorationFilterMode
import com.example.myapplication.SampleImage
import com.example.myapplication.StudioUiState
import com.example.myapplication.degradationCategories
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.ui.theme.StudioAccent
import com.example.myapplication.ui.theme.StudioBorder
import com.example.myapplication.ui.theme.StudioCanvas
import com.example.myapplication.ui.theme.StudioDanger
import com.example.myapplication.ui.theme.StudioScrim
import com.example.myapplication.ui.theme.StudioSuccess
import com.example.myapplication.ui.theme.StudioSurface
import com.example.myapplication.ui.theme.StudioSurfaceElevated
import com.example.myapplication.ui.theme.StudioTextPrimary
import com.example.myapplication.ui.theme.StudioTextSecondary

/**
 * Màn hình chính kiểu CapCut:
 * 1. Nút lớn "+ Tạo dự án mới" (Chọn ảnh từ máy để khôi phục ngay)
 * 2. Cụm công cụ phụ: "Bộ ảnh mẫu AI (Demo)" & "Benchmark / YOLOv8"
 * 3. Lưới "Dự án đã chỉnh sửa (Projects)" lưu lại toàn bộ các ảnh đã khôi phục trước đó
 */
@Composable
fun StudioHomeScreen(
    state: StudioUiState,
    snackbarHostState: SnackbarHostState,
    onCreateNewProject: () -> Unit,
    onOpenSavedProject: (EditedProject) -> Unit,
    onDeleteProject: (String) -> Unit,
    onSelectDemoCategory: (Int) -> Unit,
    onPickDemoSample: (Int, String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDeleteMode by rememberSaveable { mutableStateOf(false) }
    var showDemoSheet by rememberSaveable { mutableStateOf(false) }

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
            HomeTopBar(
                state = state,
                onOpenDiagnostics = onOpenDiagnostics
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. HERO CARD: "+ Tạo dự án mới" (Giống nút Create Project lớn của CapCut)
                item(span = { GridItemSpan(2) }) {
                    CreateProjectHeroCard(onClick = onCreateNewProject)
                }

                // 2. HÀNG PHÍM TẮT PHỤ: "Bộ ảnh mẫu Demo" & "Benchmark / YOLOv8"
                item(span = { GridItemSpan(2) }) {
                    QuickToolsRow(
                        onOpenDemoSamples = { showDemoSheet = true },
                        onOpenDiagnostics = onOpenDiagnostics
                    )
                }

                // 3. TIÊU ĐỀ "Dự án đã chỉnh sửa (N)" + Nút bật chế độ Xóa (Trash)
                item(span = { GridItemSpan(2) }) {
                    ProjectsSectionHeader(
                        count = state.savedProjects.size,
                        isDeleteMode = isDeleteMode,
                        onToggleDeleteMode = { isDeleteMode = !isDeleteMode }
                    )
                }

                // 4. DANH SÁCH DỰ ÁN ĐÃ CHỈNH SỬA HOẶC TRẠNG THÁI TRỐNG
                if (state.savedProjects.isEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        EmptyProjectsCard(
                            onCreateNewProject = onCreateNewProject,
                            onOpenDemoSamples = { showDemoSheet = true }
                        )
                    }
                } else {
                    items(
                        items = state.savedProjects,
                        key = { it.id }
                    ) { project ->
                        SavedProjectCard(
                            project = project,
                            isDeleteMode = isDeleteMode,
                            onClick = {
                                if (isDeleteMode) {
                                    onDeleteProject(project.id)
                                } else {
                                    onOpenSavedProject(project)
                                }
                            },
                            onDelete = { onDeleteProject(project.id) }
                        )
                    }
                }
            }
        }
    }

    if (showDemoSheet) {
        SampleDatasetPickerSheet(
            categories = degradationCategories,
            selectedCategory = state.selectedCategory,
            samples = state.samples,
            onSelectCategory = onSelectDemoCategory,
            onSelectSample = { catIdx, fileName ->
                showDemoSheet = false
                onPickDemoSample(catIdx, fileName)
            },
            onDismiss = { showDemoSheet = false }
        )
    }
}

@Composable
private fun HomeTopBar(
    state: StudioUiState,
    onOpenDiagnostics: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(StudioAccent.copy(alpha = 0.2f))
                    .border(1.dp, StudioAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_studio_sparkle),
                    contentDescription = null,
                    tint = StudioAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column {
                Text(
                    text = "PromptIR Studio",
                    style = MaterialTheme.typography.titleLarge,
                    color = StudioTextPrimary
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(StudioSurfaceElevated)
                    .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onOpenDiagnostics)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${state.executionMode.shortLabel} • ${if (state.fastMode) "⚡ Nhanh" else "HD"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioAccent
                )
            }

            StudioIconButton(
                iconRes = R.drawable.ic_studio_insights,
                contentDescription = "Cấu hình AI & Benchmark",
                onClick = onOpenDiagnostics
            )
        }
    }
}

/**
 * Thẻ lớn "+ Tạo dự án mới" lấy cảm hứng trực tiếp từ nút "+ Create project" của CapCut.
 */
@Composable
private fun CreateProjectHeroCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val heroGradient = Brush.linearGradient(
        colors = listOf(
            StudioAccent.copy(alpha = 0.42f),
            StudioSurfaceElevated,
            StudioSuccess.copy(alpha = 0.35f)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(148.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(heroGradient)
            .border(1.dp, StudioAccent.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioTextPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_studio_add),
                        contentDescription = null,
                        tint = StudioCanvas,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Text(
                    text = "Tạo dự án mới",
                    style = MaterialTheme.typography.titleLarge,
                    color = StudioTextPrimary
                )
            }

            Text(
                text = "Chọn ảnh từ máy để khử mờ, khử nhiễu, tăng sáng bằng AI",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Hàng nút phụ ngay dưới nút Tạo dự án (giống "Long video to shorts" trên CapCut):
 * Cho phép mở nhanh kho ảnh mẫu Demo hoặc bảng đo Benchmark & YOLOv8.
 */
@Composable
private fun QuickToolsRow(
    onOpenDemoSamples: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .clip(PillShape)
                .background(StudioSurface)
                .border(1.dp, StudioBorder, PillShape)
                .clickable(role = Role.Button, onClick = onOpenDemoSamples)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(StudioAccent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_studio_photo_library),
                    contentDescription = null,
                    tint = StudioAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column {
                Text(
                    text = "Thử ảnh mẫu AI",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "5 bộ dữ liệu chuẩn",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary,
                    maxLines = 1
                )
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .clip(PillShape)
                .background(StudioSurface)
                .border(1.dp, StudioBorder, PillShape)
                .clickable(role = Role.Button, onClick = onOpenDiagnostics)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(StudioSuccess.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_studio_insights),
                    contentDescription = null,
                    tint = StudioSuccess,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column {
                Text(
                    text = "Benchmark & YOLO",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Đo GPU/CPU & mAP",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Thanh tiêu đề "Dự án đã chỉnh sửa (N)" kèm nút Xóa (Trash) giống CapCut.
 */
@Composable
private fun ProjectsSectionHeader(
    count: Int,
    isDeleteMode: Boolean,
    onToggleDeleteMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Dự án đã chỉnh sửa ($count)",
            style = MaterialTheme.typography.titleMedium,
            color = StudioTextPrimary
        )

        if (count > 0) {
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .clip(PillShape)
                    .background(if (isDeleteMode) StudioDanger.copy(alpha = 0.18f) else StudioSurface)
                    .border(
                        1.dp,
                        if (isDeleteMode) StudioDanger else StudioBorder,
                        PillShape
                    )
                    .clickable(role = Role.Button, onClick = onToggleDeleteMode)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(
                        if (isDeleteMode) R.drawable.ic_studio_close else R.drawable.ic_studio_delete
                    ),
                    contentDescription = null,
                    tint = if (isDeleteMode) StudioDanger else StudioTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (isDeleteMode) "Xong" else "Xóa bớt",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isDeleteMode) StudioDanger else StudioTextSecondary
                )
            }
        }
    }
}

/**
 * Thẻ hiển thị 1 dự án đã chỉnh sửa trong lưới (giống thẻ dự án của CapCut: ảnh vuông bo góc 16dp,
 * mã tên dự án và thông số dung lượng | thời gian bên dưới).
 */
@Composable
private fun SavedProjectCard(
    project: EditedProject,
    isDeleteMode: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ViewportShape)
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(ViewportShape)
                .background(StudioSurface)
                .border(
                    width = 1.dp,
                    color = if (isDeleteMode) StudioDanger.copy(alpha = 0.7f) else StudioBorder,
                    shape = ViewportShape
                )
        ) {
            Image(
                bitmap = project.thumbnail.asImageBitmap(),
                contentDescription = "Dự án ${project.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Nhãn chế độ lọc đã dùng ở góc trên trái
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(StudioScrim)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = project.modeTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Nút xóa nhanh góc trên phải (vùng chạm chuẩn 48dp)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
                    .clickable(role = Role.Button, onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isDeleteMode) StudioDanger else StudioScrim),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_studio_delete),
                        contentDescription = "Xóa dự án ${project.title}",
                        tint = if (isDeleteMode) Color.White else StudioTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // Nếu có chỉ số tăng PSNR thì hiện ở góc dưới phải
            val pre = project.initialPsnr
            val post = project.restoredPsnr
            if (pre != null && post != null) {
                DeltaBadge(
                    value = post - pre,
                    decimals = 1,
                    suffix = " dB",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                )
            }
        }

        Text(
            text = project.title,
            style = MaterialTheme.typography.titleSmall,
            color = StudioTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        Text(
            text = project.metaSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = StudioTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

/**
 * Trạng thái khi chưa có ảnh nào trong mục "Dự án đã chỉnh sửa".
 */
@Composable
private fun EmptyProjectsCard(
    onCreateNewProject: () -> Unit,
    onOpenDemoSamples: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = StudioSurface,
        shape = ViewportShape,
        border = StudioBorderStroke
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(StudioSurfaceElevated)
                    .border(1.dp, StudioBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_studio_photo_library),
                    contentDescription = null,
                    tint = StudioTextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = "Chưa có ảnh nào được chỉnh sửa",
                style = MaterialTheme.typography.titleSmall,
                color = StudioTextPrimary
            )

            Text(
                text = "Các bức ảnh sau khi bạn khôi phục sẽ tự động lưu tại đây để bạn xem lại, so sánh Trước/Sau hoặc chỉnh sửa tiếp bất cứ lúc nào.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StudioSecondaryButton(
                    text = "Chọn ảnh từ máy",
                    iconRes = R.drawable.ic_studio_add,
                    onClick = onCreateNewProject
                )

                StudioSecondaryButton(
                    text = "Mở ảnh mẫu",
                    onClick = onOpenDemoSamples
                )
            }
        }
    }
}

/**
 * Bottom Sheet chọn ảnh mẫu Demo (tách riêng hoàn toàn khỏi thanh chọn chế độ lọc của người dùng).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleDatasetPickerSheet(
    categories: List<DegradationCategory>,
    selectedCategory: Int,
    samples: List<SampleImage>,
    onSelectCategory: (Int) -> Unit,
    onSelectSample: (Int, String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(
                title = "Bộ ảnh mẫu kiểm thử (Demo & Benchmark)",
                subtitle = "Dùng khi bạn muốn thử nghiệm nhanh hoặc đo chỉ số PSNR / YOLOv8 với ảnh chuẩn Ground Truth."
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(categories) { index, cat ->
                    DegradationPill(
                        title = cat.title,
                        caption = cat.dataset,
                        selected = index == selectedCategory,
                        enabled = true,
                        onClick = { onSelectCategory(index) }
                    )
                }
            }

            Text(
                text = "Chọn một ảnh mẫu bên dưới để mở vào trình chỉnh sửa:",
                style = MaterialTheme.typography.labelLarge,
                color = StudioTextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                samples.forEach { sample ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(ViewportShape)
                            .background(StudioSurfaceElevated)
                            .border(1.dp, StudioBorder, ViewportShape)
                            .clickable(role = Role.Button) {
                                onSelectSample(selectedCategory, sample.fileName)
                            }
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            bitmap = sample.thumbnail.asImageBitmap(),
                            contentDescription = sample.label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Text(
                            text = "Mẫu ${sample.label}",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary
                        )
                    }
                }
            }

            StudioPrimaryButton(
                text = "Mở ngay bộ mẫu ${categories[selectedCategory].title}",
                onClick = {
                    val firstFile = samples.firstOrNull()?.fileName.orEmpty()
                    onSelectSample(selectedCategory, firstFile)
                },
                iconRes = R.drawable.ic_studio_auto_fix,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F0F12, widthDp = 390, heightDp = 844)
@Composable
private fun StudioHomeScreenPreview() {
    val thumb1 = remember {
        createBitmap(240, 240).apply {
            val c = AndroidCanvas(this)
            c.drawColor(android.graphics.Color.rgb(42, 48, 72))
            val p = AndroidPaint().apply {
                color = android.graphics.Color.rgb(99, 102, 241)
                isAntiAlias = true
            }
            c.drawCircle(120f, 120f, 68f, p)
        }
    }
    val thumb2 = remember {
        createBitmap(240, 240).apply {
            val c = AndroidCanvas(this)
            c.drawColor(android.graphics.Color.rgb(32, 58, 52))
            val p = AndroidPaint().apply {
                color = android.graphics.Color.rgb(16, 185, 129)
                isAntiAlias = true
            }
            c.drawRect(50f, 60f, 190f, 180f, p)
        }
    }

    val mockProjects = listOf(
        EditedProject(
            id = "proj_1",
            title = "0719",
            modeKey = RestorationFilterMode.DEBLUR_SHARPEN.key,
            modeTitle = "Khử mờ & Làm nét",
            createdAt = 0L,
            updatedAt = 0L,
            width = 1024,
            height = 768,
            sizeBytes = 1540_000L,
            durationMs = 820L,
            initialPsnr = 22.4,
            restoredPsnr = 28.9,
            thumbnail = thumb1
        ),
        EditedProject(
            id = "proj_2",
            title = "0618",
            modeKey = RestorationFilterMode.LOW_LIGHT_BOOST.key,
            modeTitle = "Tăng sáng",
            createdAt = 0L,
            updatedAt = 0L,
            width = 960,
            height = 960,
            sizeBytes = 2120_000L,
            durationMs = 1150L,
            thumbnail = thumb2
        )
    )

    MyApplicationTheme {
        StudioHomeScreen(
            state = StudioUiState(savedProjects = mockProjects),
            snackbarHostState = remember { SnackbarHostState() },
            onCreateNewProject = {},
            onOpenSavedProject = {},
            onDeleteProject = {},
            onSelectDemoCategory = {},
            onPickDemoSample = { _, _ -> },
            onOpenDiagnostics = {}
        )
    }
}
