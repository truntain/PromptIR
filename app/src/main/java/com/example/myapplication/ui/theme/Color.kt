package com.example.myapplication.ui.theme

import androidx.compose.ui.graphics.Color

// Design tokens lấy từ DESIGN.md (Dark Studio First).
// Mọi màn hình chỉ được dùng các token này, không viết mã hex rời rạc trong UI.

/** Nền canvas / viewport ảnh */
val StudioCanvas = Color(0xFF0F0F12)

/** Bottom sheet, card, thanh điều khiển */
val StudioSurface = Color(0xFF18181D)

/** Pill đang chọn, modal, vùng tương tác nổi */
val StudioSurfaceElevated = Color(0xFF23232A)

val StudioTextPrimary = Color(0xFFF4F4F6)
val StudioTextSecondary = Color(0xFF9CA3AF)

/** Hành động AI / CTA */
val StudioAccent = Color(0xFF6366F1)

/** Trạng thái hoàn tất, chỉ số cải thiện */
val StudioSuccess = Color(0xFF10B981)

/** Viền và đường phân cách 1dp */
val StudioBorder = Color(0xFF27272A)

/** Chỉ số giảm / lỗi (đỏ trầm, không chói trên nền tối) */
val StudioDanger = Color(0xFFF87171)

/** Lớp phủ dùng cho nhãn đặt trên ảnh */
val StudioScrim = Color(0xB30F0F12)
