package com.example.myapplication.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.ExecutionMode
import com.example.myapplication.R
import com.example.myapplication.SampleImage
import com.example.myapplication.ui.theme.StudioAccent
import com.example.myapplication.ui.theme.StudioBorder
import com.example.myapplication.ui.theme.StudioCanvas
import com.example.myapplication.ui.theme.StudioDanger
import com.example.myapplication.ui.theme.StudioScrim
import com.example.myapplication.ui.theme.StudioSuccess
import com.example.myapplication.ui.theme.StudioSurfaceElevated
import com.example.myapplication.ui.theme.StudioTextPrimary
import com.example.myapplication.ui.theme.StudioTextSecondary
import java.util.Locale

// Bo góc theo DESIGN.md
val PillShape = RoundedCornerShape(12.dp)
val ViewportShape = RoundedCornerShape(16.dp)
val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

val ExecutionMode.shortLabel: String
    get() = when (this) {
        ExecutionMode.FULL_GPU -> "GPU"
        ExecutionMode.PARTIAL_GPU_CPU -> "GPU + CPU"
        ExecutionMode.FULL_CPU -> "CPU"
    }

val ExecutionMode.description: String
    get() = when (this) {
        ExecutionMode.FULL_GPU -> "Nhanh nhất nếu máy hỗ trợ GPU delegate"
        ExecutionMode.PARTIAL_GPU_CPU -> "GPU kết hợp 4 luồng CPU"
        ExecutionMode.FULL_CPU -> "4 luồng CPU với XNNPACK, chạy được trên mọi máy"
    }

fun Double.format(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)

/** Nút icon tròn có vùng chạm 48dp. */
@Composable
fun StudioIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = StudioTextPrimary
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) tint else StudioTextSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Thẻ chọn bộ suy giảm. Trạng thái chọn dùng Surface Elevated + viền accent. */
@Composable
fun DegradationPill(
    title: String,
    caption: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(48.dp)
            .clip(PillShape)
            .background(if (selected) StudioSurfaceElevated else Color.Transparent)
            .border(1.dp, if (selected) StudioAccent else StudioBorder, PillShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled || selected) 1f else 0.5f)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) StudioTextPrimary else StudioTextSecondary
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) StudioAccent else StudioTextSecondary.copy(alpha = 0.7f)
        )
    }
}

/** Ô ảnh mẫu 48dp. */
@Composable
fun SampleThumbnail(
    sample: SampleImage,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(PillShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) StudioAccent else StudioBorder,
                shape = PillShape
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled || selected) 1f else 0.5f)
    ) {
        Image(
            bitmap = sample.thumbnail.asImageBitmap(),
            contentDescription = "Ảnh mẫu ${sample.label}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .padding(if (selected) 2.dp else 1.dp)
                .clip(RoundedCornerShape(10.dp))
        )
    }
}

/** Ô mở thư viện ảnh của máy. */
@Composable
fun GalleryTile(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(PillShape)
            .background(StudioSurfaceElevated)
            .border(if (selected) 2.dp else 1.dp, if (selected) StudioAccent else StudioBorder, PillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_studio_photo_library),
            contentDescription = "Mở ảnh từ thư viện",
            tint = if (selected) StudioAccent else StudioTextSecondary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Nút phụ dạng viền, cao 48dp. */
@Composable
fun StudioSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes iconRes: Int? = null
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(PillShape)
            .background(StudioSurfaceElevated)
            .border(1.dp, StudioBorder, PillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = StudioTextPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = StudioTextPrimary)
    }
}

/**
 * Nút hành động chính.
 * Khi [inProgress] = true và có [onCancel], nút hiển thị tiến trình kèm khả năng bấm để HỦY ngay lập tức.
 */
@Composable
fun StudioPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes iconRes: Int? = null,
    progress: Float? = null,
    inProgress: Boolean = false,
    onCancel: (() -> Unit)? = null
) {
    val canClickCancel = inProgress && onCancel != null
    val active = (enabled && !inProgress) || canClickCancel

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(PillShape)
            .background(
                when {
                    inProgress -> StudioSurfaceElevated
                    enabled -> StudioAccent
                    else -> StudioSurfaceElevated
                }
            )
            .then(
                if (canClickCancel) {
                    Modifier.border(1.dp, StudioDanger.copy(alpha = 0.65f), PillShape)
                } else {
                    Modifier
                }
            )
            .clickable(
                enabled = active,
                role = Role.Button,
                onClick = {
                    if (canClickCancel) onCancel?.invoke() else onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (inProgress) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth((progress ?: 0f).coerceIn(0f, 1f))
                    .background(StudioAccent.copy(alpha = 0.35f))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (canClickCancel) Arrangement.SpaceBetween else Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (iconRes != null && !inProgress) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = if (enabled) Color.White else StudioTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        inProgress -> StudioTextPrimary
                        enabled -> Color.White
                        else -> StudioTextSecondary
                    }
                )
            }

            if (canClickCancel) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioDanger.copy(alpha = 0.2f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_studio_close),
                        contentDescription = "Hủy",
                        tint = StudioDanger,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Hủy",
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioDanger
                    )
                }
            }
        }
    }
}

/** Điều khiển phân đoạn (segmented), mỗi lựa chọn cao 48dp. */
@Composable
fun StudioSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(PillShape)
            .background(StudioCanvas)
            .border(1.dp, StudioBorder, PillShape)
            .padding(3.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) StudioSurfaceElevated else Color.Transparent)
                    .selectable(selected = selected, enabled = enabled, role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) StudioTextPrimary else StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Nhỏ gọn, đặt trên ảnh (nền mờ). Vùng chạm vẫn đạt tối thiểu 48dp. */
@Composable
fun OverlayChip(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val base = if (onClick != null) {
        modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
    } else {
        modifier.clip(RoundedCornerShape(10.dp))
    }
    Row(
        modifier = base
            .background(if (selected) StudioAccent else StudioScrim)
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

/** Huy hiệu chênh lệch: xanh khi cải thiện, đỏ khi giảm. */
@Composable
fun DeltaBadge(value: Double, decimals: Int, suffix: String = "", modifier: Modifier = Modifier) {
    val positive = value >= 0
    val color = if (positive) StudioSuccess else StudioDanger
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = (if (positive) "+" else "") + value.format(decimals) + suffix,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

/** Dòng nhãn và giá trị trong bảng phân tích. */
@Composable
fun MetricRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = StudioTextPrimary) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = StudioTextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/** Dòng so sánh trước/sau kèm huy hiệu chênh lệch. */
@Composable
fun ComparisonRow(
    label: String,
    before: Double,
    after: Double,
    decimals: Int,
    unit: String = "",
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = StudioTextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${before.format(decimals)} → ${after.format(decimals)}$unit",
            style = MaterialTheme.typography.bodyMedium,
            color = StudioTextPrimary
        )
        DeltaBadge(value = after - before, decimals = decimals)
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
        if (subtitle != null) {
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = StudioTextSecondary)
        }
    }
}

/** Đường phân cách 1dp. */
@Composable
fun StudioDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(StudioBorder)
    )
}

/** Thanh tiến trình mảnh, bo tròn. */
@Composable
fun StudioProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = StudioAccent) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(StudioBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(color)
        )
    }
}

/** Viền 1dp dùng cho bề mặt. */
val StudioBorderStroke = BorderStroke(1.dp, StudioBorder)
