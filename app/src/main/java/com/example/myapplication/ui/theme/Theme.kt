package com.example.myapplication.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ứng dụng xử lý ảnh luôn dùng nền tối trung tính (Dark Studio First),
// không dùng dynamic color để màu giao diện không làm lệch cảm nhận màu ảnh.
private val StudioColorScheme = darkColorScheme(
    primary = StudioAccent,
    onPrimary = Color.White,
    primaryContainer = StudioSurfaceElevated,
    onPrimaryContainer = StudioTextPrimary,
    secondary = StudioSuccess,
    onSecondary = Color.White,
    tertiary = StudioSuccess,
    background = StudioCanvas,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = StudioTextSecondary,
    surfaceContainer = StudioSurface,
    surfaceContainerLow = StudioSurface,
    surfaceContainerHigh = StudioSurfaceElevated,
    surfaceContainerHighest = StudioSurfaceElevated,
    inverseSurface = StudioTextPrimary,
    inverseOnSurface = StudioCanvas,
    outline = StudioBorder,
    outlineVariant = StudioBorder,
    error = StudioDanger,
    scrim = StudioScrim
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = Typography,
        content = content
    )
}
