package com.android.tv

import kotlin.math.roundToInt

/**
 * Immutable dimensions shared by TV cards and the row that lays them out.
 *
 * Values are expressed in physical pixels because ImageCardView's main image
 * dimensions and the preview TextureView are both laid out in pixels here.
 */
data class TvCardMetrics(
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val itemSpacingPx: Int
) {
    companion object {
        private const val IMAGE_WIDTH_RATIO = 0.15f
        private const val IMAGE_ASPECT_WIDTH = 16f
        private const val IMAGE_ASPECT_HEIGHT = 9f
        // Leave room for Leanback's own focus zoom in addition to the card's
        // 1.05 focus scale, so adjacent cards keep visual breathing room.
        private const val SPACING_RATIO = 0.12f

        // At the narrowest supported TV width this still leaves room for
        // readable labels while fitting roughly five cards across the row.
        private const val MIN_IMAGE_WIDTH_PX = 220
        private const val MAX_IMAGE_WIDTH_PX = 384
        private const val MIN_ITEM_SPACING_PX = 16
        private const val MAX_ITEM_SPACING_PX = 48

        // Non-home presenters do not have a display width available. Retain
        // the former card size for those callers until they opt into metrics.
        private const val LEGACY_IMAGE_WIDTH_PX = 313
        private const val LEGACY_IMAGE_HEIGHT_PX = 176
        private const val LEGACY_ITEM_SPACING_PX = 24

        fun forScreenWidth(screenWidthPx: Int): TvCardMetrics {
            val imageWidthPx = (screenWidthPx.coerceAtLeast(1) * IMAGE_WIDTH_RATIO)
                .roundToInt()
                .coerceIn(MIN_IMAGE_WIDTH_PX, MAX_IMAGE_WIDTH_PX)
            val imageHeightPx =
                (imageWidthPx * IMAGE_ASPECT_HEIGHT / IMAGE_ASPECT_WIDTH).roundToInt()
            val itemSpacingPx = (imageWidthPx * SPACING_RATIO)
                .roundToInt()
                .coerceIn(MIN_ITEM_SPACING_PX, MAX_ITEM_SPACING_PX)
            return TvCardMetrics(imageWidthPx, imageHeightPx, itemSpacingPx)
        }

        fun legacy(): TvCardMetrics = TvCardMetrics(
            imageWidthPx = LEGACY_IMAGE_WIDTH_PX,
            imageHeightPx = LEGACY_IMAGE_HEIGHT_PX,
            itemSpacingPx = LEGACY_ITEM_SPACING_PX
        )
    }
}
