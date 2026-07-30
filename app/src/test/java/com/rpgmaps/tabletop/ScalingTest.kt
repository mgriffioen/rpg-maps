package com.rpgmaps.tabletop

import com.rpgmaps.tabletop.data.ImageImporter
import com.rpgmaps.tabletop.fog.FogMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pure sizing maths, which is where an off-by-one distorts every map. */
class ScalingTest {

    @Test
    fun `subsampling leaves at least the target size to scale down from`() {
        // A typical oversized battle map.
        val sample = ImageImporter.sampleSizeFor(8000, 6000, maxDim = 2048)
        assertEquals(2, sample)
        assertTrue("8000/$sample must still be >= 2048", 8000 / sample >= 2048)
    }

    @Test
    fun `images already small enough are not subsampled`() {
        assertEquals(1, ImageImporter.sampleSizeFor(1200, 900, maxDim = 2048))
        assertEquals(1, ImageImporter.sampleSizeFor(2048, 1536, maxDim = 2048))
    }

    @Test
    fun `subsample is always a power of two`() {
        listOf(3000, 5000, 9000, 16000).forEach { width ->
            val sample = ImageImporter.sampleSizeFor(width, width / 2, maxDim = 2048)
            assertEquals(
                "sample $sample for width $width is not a power of two",
                0,
                sample and (sample - 1),
            )
        }
    }

    @Test
    fun `fog mask preserves aspect ratio`() {
        val (w, h) = FogMask.sizeFor(8000, 6000)
        assertEquals(FogMask.FOG_MAX_DIM, w)
        val sourceAspect = 8000f / 6000f
        val maskAspect = w.toFloat() / h.toFloat()
        assertTrue(
            "aspect drifted: $sourceAspect vs $maskAspect",
            abs(sourceAspect - maskAspect) < 0.01f,
        )
    }

    @Test
    fun `small maps keep a one to one fog mask`() {
        assertEquals(800 to 600, FogMask.sizeFor(800, 600))
    }

    @Test
    fun `portrait maps are capped on their long edge`() {
        val (w, h) = FogMask.sizeFor(2000, 6000)
        assertEquals(FogMask.FOG_MAX_DIM, h)
        assertTrue("width should shrink with the height, got $w", w in 500..520)
    }

    @Test
    fun `degenerate sizes never produce a zero dimension`() {
        val (w, h) = FogMask.sizeFor(4000, 1)
        assertTrue("width was $w", w >= 1)
        assertTrue("height was $h", h >= 1)
    }
}
