package com.example.myapplication.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.myapplication.R
import com.example.myapplication.ui.theme.StudioAccent
import com.example.myapplication.ui.theme.StudioBorder
import com.example.myapplication.ui.theme.StudioCanvas
import com.example.myapplication.ui.theme.StudioDanger
import com.example.myapplication.ui.theme.StudioScrim
import com.example.myapplication.ui.theme.StudioSurface
import com.example.myapplication.ui.theme.StudioTextPrimary
import java.util.Locale
import kotlin.math.roundToInt

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Khung so sánh ảnh trước/sau.
 * - Kéo nút tròn 48dp (hoặc chạm vào ảnh) để dời đường chia.
 * - Chụm hai ngón để phóng to tới 5x, kéo để di chuyển; chạm đúp để phóng/thu.
 * - Hai lớp ảnh dùng chung một phép biến đổi nên luôn khớp nhau khi zoom.
 */
@Composable
fun BeforeAfterViewer(
    before: Bitmap?,
    after: Bitmap?,
    modifier: Modifier = Modifier,
    showFullRestored: Boolean = false,
    isProcessing: Boolean = false,
    processingLabel: String = "",
    processingFraction: Float? = null,
    onCancelProcessing: (() -> Unit)? = null,
    emptyContent: @Composable BoxScope.() -> Unit = {}
) {
    var split by rememberSaveable { mutableFloatStateOf(0.5f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Ảnh đầu vào đổi -> về lại trạng thái gốc
    LaunchedEffect(before) {
        scale = 1f
        offset = Offset.Zero
    }
    // Có kết quả mới -> đặt đường chia ở giữa để người dùng thấy cả hai phía
    LaunchedEffect(after) {
        if (after != null) split = 0.5f
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(ViewportShape)
            .background(StudioSurface)
            .border(1.dp, StudioBorder, ViewportShape)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        if (before == null) {
            emptyContent()
            return@BoxWithConstraints
        }

        fun clamp(value: Offset, zoom: Float): Offset {
            val maxX = (zoom - 1f) * widthPx / 2f
            val maxY = (zoom - 1f) * heightPx / 2f
            return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
        }

        val comparing = after != null && !showFullRestored
        val baseImage = after ?: before
        val transform = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }

        // Lớp ảnh + cử chỉ zoom/pan/tap
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(widthPx, heightPx) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        offset = clamp(offset + pan, newScale)
                        scale = newScale
                    }
                }
                .pointerInput(comparing, widthPx) {
                    detectTapGestures(
                        onTap = { position ->
                            if (comparing) split = (position.x / widthPx).coerceIn(0f, 1f)
                        },
                        onDoubleTap = { position ->
                            if (scale > 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                // Phóng to quanh điểm chạm
                                val center = Offset(widthPx / 2f, heightPx / 2f)
                                scale = DOUBLE_TAP_ZOOM
                                offset = clamp((center - position) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                            }
                        }
                    )
                }
        ) {
            Image(
                bitmap = baseImage.asImageBitmap(),
                contentDescription = if (after != null) "Ảnh đã khôi phục" else "Ảnh gốc",
                contentScale = ContentScale.Fit,
                modifier = transform
            )

            if (comparing) {
                // Cắt theo toạ độ khung (không bị phóng to) để mép cắt luôn trùng đường chia
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipRect(right = size.width * split) {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    Image(
                        bitmap = before.asImageBitmap(),
                        contentDescription = "Ảnh gốc",
                        contentScale = ContentScale.Fit,
                        modifier = transform
                    )
                }
            }
        }

        if (comparing) {
            SplitHandle(
                splitX = widthPx * split,
                split = split,
                onDrag = { dx -> split = (split + dx / widthPx).coerceIn(0f, 1f) }
            )

            if (!isProcessing) {
                CornerLabel("Trước", Alignment.BottomStart, visible = split > 0.12f)
                CornerLabel("Sau", Alignment.BottomEnd, visible = split < 0.88f)
            }
        } else if (showFullRestored && after != null && !isProcessing) {
            CornerLabel("Ảnh đã làm rõ (Đầy đủ)", Alignment.BottomEnd, visible = true)
        }

        // Mức zoom hiện tại, chạm để về 1x
        AnimatedVisibility(
            visible = scale > 1.01f && !isProcessing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        ) {
            OverlayChip(onClick = {
                scale = 1f
                offset = Offset.Zero
            }) {
                Text(
                    text = String.format(Locale.US, "%.1f×", scale),
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextPrimary
                )
                Text(
                    text = "Về 1×",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextPrimary.copy(alpha = 0.7f)
                )
            }
        }

        if (isProcessing) {
            ScanningOverlay(
                label = processingLabel,
                fraction = processingFraction,
                onCancel = onCancelProcessing
            )
        }
    }
}

@Composable
private fun SplitHandle(
    splitX: Float,
    split: Float,
    onDrag: (Float) -> Unit
) {
    // Đường chia 2dp
    Box(
        modifier = Modifier
            .offset { IntOffset((splitX - 1.dp.toPx()).roundToInt(), 0) }
            .width(2.dp)
            .fillMaxHeight()
            .background(StudioTextPrimary)
    )

    // Dải kéo 48dp phủ hết chiều cao, nút tròn 48dp ở giữa
    Box(
        modifier = Modifier
            .offset { IntOffset((splitX - 24.dp.toPx()).roundToInt(), 0) }
            .width(48.dp)
            .fillMaxHeight()
            .semantics {
                contentDescription = "Thanh so sánh trước và sau"
                stateDescription = "${(split * 100).roundToInt()}% ảnh gốc"
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(StudioTextPrimary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_studio_compare),
                contentDescription = null,
                tint = StudioCanvas,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun BoxScope.CornerLabel(text: String, alignment: Alignment, visible: Boolean) {
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(10.dp)
            .alpha(if (visible) 1f else 0f)
            .clip(RoundedCornerShape(8.dp))
            .background(StudioScrim)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = StudioTextPrimary)
    }
}

/**
 * Hiệu ứng tia quét chạy dọc trên ảnh gốc trong lúc model đang xử lý,
 * tối ưu bằng phần cứng (graphicsLayer) kèm nút Hủy trực tiếp trên khung ảnh.
 */
@Composable
private fun BoxScope.ScanningOverlay(
    label: String,
    fraction: Float?,
    onCancel: (() -> Unit)? = null
) {
    val transition = rememberInfiniteTransition(label = "scan")
    val position by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanPosition"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioCanvas.copy(alpha = 0.25f))
    ) {
        val totalHeightPx = constraints.maxHeight.toFloat()
        val scanGlowBrush = remember {
            Brush.verticalGradient(
                colors = listOf(Color.Transparent, StudioAccent.copy(alpha = 0.35f), StudioAccent)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .graphicsLayer {
                    translationY = (position * totalHeightPx) - 56.dp.toPx()
                }
                .background(scanGlowBrush)
        )
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(StudioScrim)
            .border(1.dp, StudioBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = StudioTextPrimary)
            if (onCancel != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioDanger.copy(alpha = 0.22f))
                        .border(1.dp, StudioDanger.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onCancel)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_studio_close),
                        contentDescription = "Hủy xử lý",
                        tint = StudioDanger,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Hủy",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioDanger
                    )
                }
            }
        }
        if (fraction != null) {
            StudioProgressBar(fraction = fraction, modifier = Modifier.width(220.dp))
        }
    }
}
