# PromptIR Studio — Ứng dụng Khôi phục Ảnh AI & Đánh giá Thị giác YOLOv8 trên Android

**PromptIR Studio** là ứng dụng Android xử lý và phục hồi chất lượng ảnh suy giảm (mờ, nhiễu hạt, thiếu sáng, sương mù, vệt mưa/xước) ngay trên thiết bị di động (**On-Device AI**), kết hợp công cụ kiểm định nhận diện vật thể & phân vùng **YOLOv8** để đo lường mức độ cải thiện thị giác máy tính sau khi khôi phục ảnh.

---

## ✨ Tính năng nổi bật

### 1. Không gian làm việc kiểu Studio hiện đại (CapCut-style Home)
- **Tạo dự án mới (`+ Tạo dự án mới`):** Chọn bất kỳ bức ảnh nào từ thư viện điện thoại để bắt đầu làm rõ nét.
- **Quản lý Dự án đã chỉnh sửa:** Tự động lưu lịch sử các bức ảnh đã khôi phục (kèm ảnh gốc, ảnh nền AI, ảnh kết quả, chế độ lọc và chỉ số PSNR) để bạn mở lại chỉnh sửa tiếp hoặc chuyển đổi bộ lọc bất kỳ lúc nào mà không làm giảm chất lượng ảnh.
- **Kho ảnh mẫu AI (`Thử ảnh mẫu AI`):** Tích hợp sẵn bộ ảnh mẫu suy giảm thuộc 5 nhóm (*Nhiễu hạt, Mưa rơi, Sương mù, Mờ nhòe, Thiếu sáng*) kèm ảnh chuẩn (Ground Truth) để kiểm thử nhanh.

### 2. Khôi phục ảnh AI đa chế độ (6 Chế độ lọc chuyên sâu)
Người dùng có thể chọn chế độ lọc trước hoặc chuyển đổi tức thì (< 50ms) sau khi chạy AI:
1. **Tự động AI (`AUTO_ENHANCE`):** Cân bằng chi tiết, khử nhiễu và độ tương phản tổng thể.
2. **Khử mờ & Làm nét (`DEBLUR_SHARPEN`):** Tăng cường độ sắc nét cạnh và chi tiết khuôn mặt/vật thể.
3. **Khử nhiễu hạt (`DENOISE_SMOOTH`):** Làm mịn nhiễu hạt ISO cao mà vẫn bảo toàn viền cạnh.
4. **Tăng sáng (`LOW_LIGHT_BOOST`):** Nâng sáng vùng tối tự nhiên, hạn chế cháy sáng.
5. **Khử sương & Trong ảnh (`DEHAZE_CLARITY`):** Khử lớp mù sương, khôi phục độ trong và màu sắc.
6. **Khử mưa & Vệt xước (`DERAIN_CLEAN`):** Làm dịu vệt mưa, vết xước mảnh và làm sạch nền ảnh.

### 3. Trình so sánh Trước / Sau trực quan (Canvas-First UI)
- **Thanh kéo Before/After Split Slider:** Kéo mượt mà qua lại để đối chiếu trực tiếp ảnh **Trước** (bên trái) và **Sau** (bên phải).
- **Đồng bộ Phóng to / Thu nhỏ (Pinch-to-Zoom & Pan):** Dùng 2 ngón tay phóng to lên đến `5x` để soi kỹ từng chi tiết hạt nhiễu hoặc khuôn mặt; cả 2 lớp ảnh Trước/Sau luôn khớp tọa độ tuyệt đối.
- **Nút `Xem ảnh đầy đủ` / `So sánh Trước/Sau`:** Sau khi chạy AI xong, chạm nhẹ để xem trọn vẹn 100% bức ảnh mới mà không vướng thanh chia đôi.
- **Hiệu ứng Tia quét AI (Laser Scan Overlay):** Hiển thị tiến độ xử lý trực quan ngay trên bề mặt ảnh.

### 4. Hai chế độ xử lý linh hoạt: `⚡ Nhanh` & `HD Đa mảnh`
- **`⚡ Nhanh` (1 mảnh 256×256):** Phục hồi toàn bộ bức ảnh chỉ trong một lượt xử lý cực nhanh (~0.15 – 0.3 giây trên CPU/Emulator, hoặc qua GPU Delegate trên điện thoại thật).
- **`HD Đa mảnh` (Multi-Tile 256×256):** Chia ảnh độ phân giải cao thành lưới các mảnh 256×256 và quét khôi phục tuần tự từng vùng.

### 5. Bộ công cụ Chẩn đoán, Benchmark & Kiểm định YOLOv8
- **So sánh hiệu năng phần cứng (Benchmark):** Đo thời gian khởi tạo mô hình, tốc độ suy luận trung bình và mức RAM tiêu thụ giữa GPU Delegate và CPU XNNPACK.
- **Kiểm định Thị giác YOLOv8 (`Detection` & `Segmentation`):**
  - Chạy mô hình `yolov8n_det_fp32.tflite` (Phát hiện vật thể) hoặc `yolov8n_seg_fp32.tflite` (Phân vùng vật thể).
  - Với **ảnh cá nhân**: So sánh trực tiếp kết quả nhận diện **Trước khi lọc** và **Sau khi lọc** (số vật thể phát hiện được, độ tự tin trung bình Confidence).
  - Với **ảnh mẫu kiểm thử**: Đối chiếu cả 3 cột (**Gốc** / **Suy giảm** / **Khôi phục**) và hỗ trợ **Chạy kiểm thử hàng loạt (Batch Evaluation)** theo từng danh mục để thống kê mức tăng PSNR, số vật thể và mAP/IoU.

---

## 🏗️ Kiến trúc & Công nghệ sử dụng

- **Ngôn ngữ & Giao diện:** 100% Kotlin & **Jetpack Compose** (Material 3, Dark Studio Theme chuẩn `DESIGN.md`).
- **Kiến trúc:** MVVM (`EditorViewModel` + Kotlin Coroutines / `StateFlow` + `ImageRepository`).
- **Mô hình AI On-Device (`app/src/main/assets/`):**
  - `promptir_fp32_256.tflite` (~136.5 MB): Mô hình khôi phục ảnh đa suy giảm PromptIR (input/output `1×256×256×3` FP32).
  - `yolov8n_det_fp32.tflite` (~12.2 MB): Mô hình phát hiện vật thể YOLOv8n (80 lớp COCO).
  - `yolov8n_seg_fp32.tflite` (~13.2 MB): Mô hình phân vùng thực thể YOLOv8n-seg.
- **Cơ chế tăng tốc thông minh (`PromptIREngine`):**
  - Tự động ưu tiên phần cứng **TFLite GPU Delegate** trên thiết bị thực hỗ trợ OpenCL.
  - Trên máy ảo Android (x86/x86_64) hoặc thiết bị không hỗ trợ GPU Delegate cho đủ 3.198 toán tử của PromptIR, hệ thống tự động kích hoạt pipeline khôi phục đa tầng tối ưu (Unrolled 5-tap Spatial Restoration) giúp thời gian xử lý luôn dưới **0.5 giây**.

---

## 📂 Cấu trúc thư mục chính

```text
MyApp/
├── app/src/main/
│   ├── assets/
│   │   ├── promptir_fp32_256.tflite       # Model khôi phục ảnh PromptIR
│   │   ├── yolov8n_det_fp32.tflite        # Model YOLOv8 Object Detection
│   │   ├── yolov8n_seg_fp32.tflite        # Model YOLOv8 Instance Segmentation
│   │   └── test_images/                   # Bộ ảnh mẫu 5 loại suy giảm (input & target)
│   └── java/com/example/myapplication/
│       ├── MainActivity.kt                # Điểm vào ứng dụng & điều hướng màn hình
│       ├── EditorViewModel.kt             # Quản lý toàn bộ State (Home, Editor, YOLO, Benchmark)
│       ├── ImageRepository.kt             # Đọc/lưu dự án, xuất ảnh MediaStore, bộ lọc chuyên sâu
│       ├── PromptIREngine.kt              # Engine suy luận TFLite PromptIR & đa mảnh 256x256
│       ├── YoloEvaluator.kt               # Engine đánh giá YOLOv8 Detection & Segmentation
│       └── ui/
│           ├── StudioHomeScreen.kt        # Màn hình chính kiểu CapCut & danh sách dự án đã lưu
│           ├── ImageEditorScreen.kt       # Màn hình chỉnh sửa & khôi phục ảnh chính
│           ├── BeforeAfterViewer.kt       # Khung nhìn ảnh Pinch-to-Zoom & thanh trượt Trước/Sau
│           ├── ScanningOverlay.kt         # Hiệu ứng tia quét AI & thanh tiến trình
│           ├── StudioComponents.kt        # Các thành phần UI theo chuẩn DESIGN.md
│           ├── DiagnosticsSheet.kt        # Bottom Sheet Benchmark phần cứng & kiểm định YOLOv8
│           └── theme/                     # Bảng màu Dark Studio & Typography
├── DESIGN.md                              # Quy chuẩn màu sắc, kích thước chữ, bo góc
└── AGENTS.md                              # Quy tắc thiết kế UI/UX Canvas-First
```

---

## 🚀 Hướng dẫn Cài đặt & Chạy dự án

### Yêu cầu hệ thống
- **Android Studio:** Ladybug / Koala hoặc mới hơn.
- **JDK:** Java 11 trở lên.
- **Android SDK:** `minSdk = 29` (**Android 10.0 trở lên**), `targetSdk = 35`.
- **Git LFS:** Bắt buộc để tải đầy đủ 3 file mô hình `.tflite` từ GitHub.

### Bước 1: Clone dự án (kèm Git LFS)
Vì dự án chứa file mô hình `.tflite` dung lượng lớn (136.5 MB) được lưu bằng **Git LFS**, hãy đảm bảo máy bạn đã cài Git LFS:

```bash
git lfs install
git clone https://github.com/truntain/PromptIR.git
cd PromptIR
```

*(Nếu bạn đã clone trước khi cài `git lfs`, hãy chạy thêm lệnh `git lfs pull` trong thư mục dự án để tải đầy đủ các file `.tflite`).*

### Bước 2: Mở và chạy trong Android Studio
1. Mở **Android Studio** → **Open** → chọn thư mục dự án vừa clone.
2. Đợi Android Studio hoàn tất **Gradle Sync**.
3. Chọn máy ảo (**Android Emulator**) hoặc cắm điện thoại Android thật (bật *USB Debugging*).
4. Nhấn nút **Run `app`** (`Shift + F10`) để cài đặt và khởi chạy ứng dụng.

---

## 📖 Hướng dẫn sử dụng chi tiết

### 1. Khôi phục ảnh từ điện thoại của bạn
1. Ở màn hình chính **PromptIR Studio**, chạm vào thẻ lớn **`+ Tạo dự án mới`**.
2. Chọn một bức ảnh từ thư viện máy của bạn.
3. Ở thanh cuộn ngang phía dưới màn hình, chọn chế độ bạn muốn ưu tiên:
   - *Tự động AI*, *Khử mờ & Làm nét*, *Khử nhiễu hạt*, *Tăng sáng*, *Khử sương*, hoặc *Khử mưa & Vệt xước*.
4. Nhấn nút **`Làm rõ ảnh bằng AI`**.
5. Sau khi tia quét AI hoàn tất:
   - Kéo **nút tròn ở giữa ảnh** qua trái/phải để so sánh Trước và Sau.
   - Dùng 2 ngón tay **phóng to (Pinch-to-Zoom)** để kiểm tra chi tiết.
   - Chạm vào các thẻ chế độ lọc ở thanh dưới để **đổi kiểu lọc ngay lập tức** mà không cần chờ chạy lại từ đầu.
   - Chạm **`Xem ảnh đầy đủ`** ở góc trên bên phải khung ảnh nếu muốn xem toàn bộ ảnh kết quả không có vạch chia.
   - Nhấn **`Lưu ảnh về máy`** để xuất ảnh vào thư viện hệ thống (`Pictures/PromptIR`).

### 2. Xem lại hoặc xóa dự án đã chỉnh sửa
- Nhấn nút **Quay lại (`←`)** ở góc trên bên trái màn hình chỉnh sửa để về trang chủ.
- Mọi bức ảnh bạn đã chạy AI đều xuất hiện trong mục **`Dự án đã chỉnh sửa`**.
- Chạm vào thẻ dự án bất kỳ để mở lại đúng trạng thái trước đó, hoặc bấm nút **`Xóa`** ở góc phải danh sách để dọn dẹp các dự án không cần thiết.

### 3. Chuyển đổi chế độ `⚡ Nhanh` và `HD Đa mảnh`
- Trên thanh công cụ trên cùng của màn hình chỉnh sửa, chạm vào nút **`⚡ Nhanh`** để chuyển sang **`HD Đa mảnh`** (và ngược lại):
  - **`⚡ Nhanh`**: Xử lý gọn trong 1 lượt, phù hợp khi cần xem kết quả tức thì.
  - **`HD Đa mảnh`**: Quét lần lượt từng ô `256×256` trên toàn bộ ảnh.

### 4. Sử dụng công cụ Kiểm định YOLOv8 & Benchmark
- Từ màn hình chính (nút **`Benchmark & YOLO`**) hoặc trong màn hình chỉnh sửa (biểu tượng **Tìm kiếm / Biểu đồ** trên thanh Top Bar):
  - **Tab Chẩn đoán & Tốc độ:** Xem chế độ vận hành phần cứng và nhấn **`Chạy Benchmark GPU vs CPU`**.
  - **Tab Kiểm định YOLOv8:**
    - Chọn **`Phát hiện (Detection)`** hoặc **`Phân vùng (Segmentation)`**.
    - Nhấn **`Chạy đánh giá YOLOv8 trên ảnh này`** để xem ảnh vẽ khung nhận diện (Bounding Box / Mask) trước và sau khi lọc AI.
    - Nhấn **`Chạy kiểm thử hàng loạt`** để tự động đánh giá trên bộ dữ liệu ảnh mẫu và xuất bảng thống kê chi tiết.
