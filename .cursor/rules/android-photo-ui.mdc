---
description: Quy tắc thiết kế UI/UX cho các màn hình trong ứng dụng khôi phục ảnh AI trên Android
globs: ["**/*.kt", "**/*.xml"]
alwaysApply: true
---

# UI/UX & ANDROID IMPLEMENTATION RULES

Khi tạo hoặc chỉnh sửa bất kỳ màn hình/component nào trong `MyApp`, Agent BẮT BUỘC tuân thủ:

1. **Tham chiếu Design System:** Luôn đọc và áp dụng đúng mã màu, kích thước chữ, bo góc trong file `@DESIGN.md`. Tuyệt đối không tự bịa mã màu hex rời rạc trong code giao diện.
2. **Bố cục Canvas-First (Ưu tiên vùng nhìn ảnh):**
   - Vùng hiển thị ảnh (Image Viewport) phải chiếm tối thiểu **65% - 75% chiều cao màn hình**.
   - Toàn bộ công cụ điều khiển (Chọn chế độ khôi phục, nút chạy AI, lưu ảnh) phải gom gọn vào **Bottom Sheet** hoặc **Bottom Control Bar** ở 1/3 dưới màn hình để thao tác bằng 1 ngón cái (Thumb-friendly).
3. **Các Component bắt buộc phải có cho App Khôi Phục Ảnh:**
   - **Before/After Split Slider:** Màn hình kết quả phải có thanh kéo dọc ở giữa ảnh (có nút tròn `48dp` ở tâm thanh kéo), cho phép kéo qua lại mượt mà để so sánh ảnh Gốc (Before - bên trái) và ảnh Đã khôi phục (After - bên phải). Hỗ trợ phóng to/thu nhỏ (Pinch-to-Zoom) đồng bộ cả 2 lớp ảnh.
   - **Degradation / Restoration Mode Selector:** Không dùng Dropdown. Phải dùng danh sách cuộn ngang (`LazyRow` hoặc `RecyclerView` ngang) hiển thị các thẻ chọn chế độ (ví dụ: *Khử nhiễu, Khử mờ, Làm nét mặt, Phục hồi vết xước, Tăng sáng*) với trạng thái Active nổi bật.
   - **AI Processing State:** Khi đang chạy model, không dùng `CircularProgressIndicator` đơn điệu giữa màn hình đen. Hãy giữ nguyên ảnh gốc trên màn hình, phủ một lớp hiệu ứng tia quét (Scanning Line / Shimmer) chạy dọc trên ảnh kèm text trạng thái rõ ràng.
4. **Chuẩn Code Android:**
   - Tách biệt rõ ràng State và UI (State Hoisting nếu dùng Jetpack Compose, hoặc ViewModel + StateFlow).
   - Luôn tạo sẵn hàm `@Preview` (với Compose) hoặc thuộc tính `tools:...` (với XML) ở chế độ Dark Mode để kiểm tra trực quan ngay trong IDE.
