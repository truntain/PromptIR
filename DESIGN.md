# DESIGN SYSTEM: AI Photo Restoration App (MyApp)

## 1. Color Palette (Dark Studio First)
Ứng dụng xử lý ảnh bắt buộc dùng nền tối trung tính để không làm lệch cảm nhận màu sắc của ảnh gốc.
- **Background Primary (Canvas/Viewport):** `#0F0F12` (Xám đen sâu, KHÔNG dùng `#000000`)
- **Surface / Bottom Sheet / Card:** `#18181D`
- **Surface Elevated (Selected Pill / Modal):** `#23232A`
- **Text Primary:** `#F4F4F6`
- **Text Secondary / Muted:** `#9CA3AF`
- **Accent Primary (AI Action / CTA):** `#6366F1` (Indigo) hoặc `#10B981` (Emerald cho trạng thái hoàn tất)
- **Border / Divider:** `#27272A` (Độ dày 1dp)

## 2. Typography & Hierarchy
- **Screen Title:** 20sp, SemiBold, Letter-spacing -0.2sp
- **Section Header / Tool Label:** 14sp, Medium, Text Secondary
- **Pill / Chip Text:** 13sp, Medium
- **Body / Status Text:** 14sp, Regular

## 3. Spacing & Touch Targets (Mobile Standard)
- **Grid System:** Bội số của 4dp (4dp, 8dp, 12dp, 16dp, 24dp).
- **Touch Target tối thiểu:** `48dp x 48dp` cho mọi nút bấm, icon, thanh trượt.
- **Corner Radius:** 
  - Card / Bottom Sheet: `24dp` (Top corners)
  - Button / Degradation Mode Pill: `12dp` hoặc `Full Capsule (50%)`
  - Image Preview Container: `16dp`
