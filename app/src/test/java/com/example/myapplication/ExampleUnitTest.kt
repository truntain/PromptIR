package com.example.myapplication

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class ExampleUnitTest {

    private fun packRgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun createDetection(
        classId: Int,
        score: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): YoloDetection = YoloDetection(
        classId = classId,
        className = "class_$classId",
        score = score,
        x1 = x1,
        y1 = y1,
        x2 = x2,
        y2 = y2,
        x1_640 = x1,
        y1_640 = y1,
        x2_640 = x2,
        y2_640 = y2
    )

    @Test
    fun reflectIndex_handlesInRangeAndOutOfBoundsCorrectly() {
        // Trường hợp length <= 1 luôn trả về 0
        assertEquals(0, PromptIREngine.reflectIndex(-5, 1))
        assertEquals(0, PromptIREngine.reflectIndex(10, 1))

        // Trong phạm vi [0, length - 1]
        for (i in 0 until 10) {
            assertEquals(i, PromptIREngine.reflectIndex(i, 10))
        }

        // Phản chiếu qua biên trái (idx < 0)
        assertEquals(1, PromptIREngine.reflectIndex(-1, 10))
        assertEquals(2, PromptIREngine.reflectIndex(-2, 10))

        // Phản chiếu qua biên phải (idx >= length)
        assertEquals(8, PromptIREngine.reflectIndex(10, 10))
        assertEquals(7, PromptIREngine.reflectIndex(11, 10))
    }

    @Test
    fun computeTileStarts_coversEntireDimensionWithOverlap() {
        // Ảnh nhỏ hơn hoặc bằng 256 -> chỉ cần 1 mảnh tại tọa độ 0
        assertArrayEquals(intArrayOf(0), PromptIREngine.computeTileStarts(256, 256, 224))

        // Ảnh 512px với patch=256, stride=224 (overlap=32):
        // Mảnh 0: [0..256], Mảnh 1: [224..480], Mảnh cuối: [256..512]
        val starts512 = PromptIREngine.computeTileStarts(512, 256, 224)
        assertArrayEquals(intArrayOf(0, 224, 256), starts512)
        assertEquals(512, starts512.last() + 256)

        // Mọi cặp mảnh liền kề phải có vùng chồng lấp >= 32px
        for (i in 0 until starts512.size - 1) {
            val overlap = (starts512[i] + 256) - starts512[i + 1]
            assertTrue("Overlap phải >= 32px nhưng nhận được $overlap", overlap >= 32)
        }
    }

    @Test
    fun createFeatherWeights1D_isSymmetricAndPeaksAtCenter() {
        val weights = PromptIREngine.createFeatherWeights1D(patchSize = 256, overlap = 32)
        assertEquals(256, weights.size)

        // Trọng số đối xứng hai đầu và dương ở mọi vị trí
        for (i in 0 until 256) {
            assertTrue(weights[i] > 0f)
            assertEquals(weights[i], weights[255 - i], 1e-6f)
        }

        // Vùng tâm [32..223] có trọng số đạt 1.0f, biên ngoài nhỏ hơn 1.0f
        assertTrue(weights[0] < weights[15])
        assertTrue(weights[15] < 1.0f)
        assertEquals(1.0f, weights[32], 1e-6f)
        assertEquals(1.0f, weights[128], 1e-6f)
        assertEquals(1.0f, weights[223], 1e-6f)
    }

    @Test
    fun computePsnrFromPixels_returnsInfinityForIdenticalAndExactDbForKnownError() {
        val imgA = IntArray(16) { packRgb(120, 150, 200) }
        val imgB = IntArray(16) { packRgb(120, 150, 200) }

        assertTrue(PromptIREngine.computePsnrFromPixels(imgA, imgB).isInfinite())

        // Mỗi kênh R, G, B lệch đúng 10 đơn vị -> MSE = 10^2 = 100
        val imgC = IntArray(16) { packRgb(130, 160, 210) }
        val expectedPsnr = 10.0 * log10((255.0 * 255.0) / 100.0) // ~28.1308 dB
        val actualPsnr = PromptIREngine.computePsnrFromPixels(imgA, imgC)
        assertEquals(expectedPsnr, actualPsnr, 1e-4)
    }

    @Test
    fun calculateIoU_computesExactIntersectionOverUnion() {
        val boxA = createDetection(classId = 0, score = 0.9f, x1 = 0f, y1 = 0f, x2 = 100f, y2 = 100f)
        val boxSame = createDetection(classId = 0, score = 0.8f, x1 = 0f, y1 = 0f, x2 = 100f, y2 = 100f)
        val boxDisjoint = createDetection(classId = 0, score = 0.8f, x1 = 200f, y1 = 200f, x2 = 300f, y2 = 300f)
        // Giao nhau vùng [50, 0, 100, 100] -> diện tích giao = 5000, diện tích hợp = 10000 + 10000 - 5000 = 15000 -> IoU = 1/3
        val boxHalfShift = createDetection(classId = 0, score = 0.8f, x1 = 50f, y1 = 0f, x2 = 150f, y2 = 100f)

        assertEquals(1.0f, YoloEvaluator.calculateIoU(boxA, boxSame), 1e-5f)
        assertEquals(0.0f, YoloEvaluator.calculateIoU(boxA, boxDisjoint), 1e-5f)
        assertEquals(1.0f / 3.0f, YoloEvaluator.calculateIoU(boxA, boxHalfShift), 1e-5f)
    }

    @Test
    fun applyNms_suppressesOverlappingSameClassAndKeepsDifferentClass() {
        val primary = createDetection(classId = 1, score = 0.95f, x1 = 10f, y1 = 10f, x2 = 110f, y2 = 110f)
        val duplicateSameClass = createDetection(classId = 1, score = 0.80f, x1 = 15f, y1 = 15f, x2 = 112f, y2 = 112f)
        val overlappingDifferentClass = createDetection(classId = 2, score = 0.85f, x1 = 12f, y1 = 12f, x2 = 110f, y2 = 110f)

        val nmsOut = YoloEvaluator.applyNms(
            listOf(duplicateSameClass, primary, overlappingDifferentClass),
            iouThreshold = 0.45f
        )

        assertEquals(2, nmsOut.size)
        assertEquals(1, nmsOut[0].classId)
        assertEquals(0.95f, nmsOut[0].score, 1e-5f)
        assertEquals(2, nmsOut[1].classId)
    }

    @Test
    fun calculateMapMetrics_scoresPerfectAndPartialPredictionsAccurately() {
        val gt1 = createDetection(classId = 0, score = 0.90f, x1 = 20f, y1 = 20f, x2 = 120f, y2 = 120f)
        val gt2 = createDetection(classId = 5, score = 0.88f, x1 = 200f, y1 = 150f, x2 = 400f, y2 = 350f)
        val gts = listOf(gt1, gt2)

        // 1. Dự đoán khớp hoàn toàn với Ground Truth -> mAP@0.5 = 1.0, mAP@0.5:0.95 = 1.0
        val (perfectMap50, perfectMap50_95) = YoloEvaluator.calculateMapMetrics(gts, gts)
        assertEquals(1.0, perfectMap50, 1e-5)
        assertEquals(1.0, perfectMap50_95, 1e-5)

        // 2. Không phát hiện được vật thể nào khi GT có vật thể -> mAP = 0.0
        val (emptyMap50, emptyMap50_95) = YoloEvaluator.calculateMapMetrics(emptyList(), gts)
        assertEquals(0.0, emptyMap50, 1e-5)
        assertEquals(0.0, emptyMap50_95, 1e-5)

        // 3. Phát hiện đúng 1 trên 2 class (class 0 đúng 100%, class 5 bỏ sót) -> mAP@0.5 = 0.5
        val partialPreds = listOf(
            createDetection(classId = 0, score = 0.92f, x1 = 20f, y1 = 20f, x2 = 120f, y2 = 120f)
        )
        val (partialMap50, _) = YoloEvaluator.calculateMapMetrics(partialPreds, gts)
        assertEquals(0.5, partialMap50, 1e-5)
    }
}
