package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TvCardMetricsTest {

    @Test
    fun common_tv_widths_scale_cards_for_five_to_six_visible_items() {
        assertEquals(
            TvCardMetrics(220, 124, 26),
            TvCardMetrics.forScreenWidth(1280)
        )
        assertEquals(
            TvCardMetrics(288, 162, 35),
            TvCardMetrics.forScreenWidth(1920)
        )
        assertEquals(
            TvCardMetrics(362, 204, 43),
            TvCardMetrics.forScreenWidth(2412)
        )
    }

    @Test
    fun image_dimensions_keep_sixteen_to_nine_ratio_after_rounding() {
        listOf(1280, 1920, 2412, 320, 4096).forEach { screenWidthPx ->
            val metrics = TvCardMetrics.forScreenWidth(screenWidthPx)
            assertTrue(
                abs(metrics.imageWidthPx * 9 - metrics.imageHeightPx * 16) <= 16
            )
            assertTrue(metrics.imageWidthPx > 0)
            assertTrue(metrics.imageHeightPx > 0)
        }
    }

    @Test
    fun invalid_and_narrow_widths_are_clamped_to_readable_bounds() {
        val zeroWidth = TvCardMetrics.forScreenWidth(0)
        val negativeWidth = TvCardMetrics.forScreenWidth(-100)
        val narrowWidth = TvCardMetrics.forScreenWidth(320)

        assertEquals(zeroWidth, negativeWidth)
        assertTrue(narrowWidth.imageWidthPx >= 220)
        assertTrue(narrowWidth.itemSpacingPx >= 16)
    }

    @Test
    fun legacy_metrics_preserve_non_home_card_dimensions() {
        assertEquals(TvCardMetrics(313, 176, 24), TvCardMetrics.legacy())
    }
}
