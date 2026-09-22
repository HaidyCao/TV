package com.android.tv

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.leanback.widget.BaseCardView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import com.bumptech.glide.Glide

/**
 * A CardPresenter is used to generate Views and bind Objects to them on demand.
 * It contains an ImageCardView.
 */
class CardPresenter(
    private val previewFrameManager: PreviewFrameManager? = null,
    private val livePreviewFrameStore: LivePreviewFrameStore? = null,
    private val isFavorite: (Movie) -> Boolean = { false },
    private val onFavoriteToggle: ((Movie) -> Unit)? = null,
    private val onCardUnbound: ((CardViewHolder) -> Unit)? = null,
    private val onCardFocusChanged: ((Movie, CardViewHolder, Boolean) -> Unit)? = null,
    private val onCardVisibilityChanged: ((Movie, CardViewHolder, Boolean) -> Unit)? = null,
    private val cardMetrics: TvCardMetrics = TvCardMetrics.legacy()
) : Presenter() {
    private var mDefaultCardImage: Drawable? = null
    private var mLiveCardImage: Drawable? = null

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        Log.d(TAG, "onCreateViewHolder")

        mDefaultCardImage = ContextCompat.getDrawable(parent.context, R.drawable.movie)
        mLiveCardImage = ContextCompat.getDrawable(parent.context, R.drawable.channel_placeholder)

        val cardView = object : PreviewCardView(parent.context, cardMetrics) {
            override fun setSelected(selected: Boolean) {
                updateCardBackgroundColor(this, selected)
                super.setSelected(selected)
            }
        }

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.elevation = CARD_ELEVATION_DP * parent.resources.displayMetrics.density
        updateCardBackgroundColor(cardView, false)
        cardView.refreshFocusVisual(animate = false)
        return CardViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val holder = viewHolder as CardViewHolder
        // Leanback may reuse a holder without a visible transition. Detach any
        // preview surface before painting the new channel into that holder.
        holder.setCardFocusChangedListener(null)
        holder.setCardVisibilityChangedListener(null)
        onCardUnbound?.invoke(holder)
        holder.resetPreviewLayer()
        holder.clearCardBinding()
        holder.resetFocusVisual()
        if (item == null) return
        val movie = item as Movie
        val cardView = holder.cardView

        Log.d(TAG, "onBindViewHolder")
        cardView.titleText = movie.title?.trim().orEmpty().ifBlank {
            cardView.context.getString(R.string.channel_placeholder_label)
        }
        cardView.setCardContentText(movie.studio.takeUnless { movie.isLive })
        cardView.setMainImageDimensions(cardMetrics.imageWidthPx, cardMetrics.imageHeightPx)
        cardView.badgeImage = if (isFavorite(movie)) {
            ContextCompat.getDrawable(cardView.context, R.drawable.ic_favorite)
        } else {
            null
        }
        cardView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                onFavoriteToggle?.invoke(movie)
                onFavoriteToggle != null
            } else {
                false
            }
        }
        holder.setCardFocusChangedListener { hasFocus ->
            onCardFocusChanged?.invoke(movie, holder, hasFocus)
        }
        
        val requestKey = "${movie.id}:${movie.videoUrl.orEmpty()}"
        holder.bindCard(requestKey)
        holder.refreshFocusVisual(animate = false)
        holder.setCardVisibilityChangedListener { isVisible ->
            onCardVisibilityChanged?.invoke(movie, holder, isVisible)
        }
        val imageView = cardView.mainImageView ?: return
        imageView.tag = requestKey
        imageView.visibility = View.VISIBLE

        val videoUrl = movie.videoUrl
        val cachedLiveFrame = if (movie.isLive && !videoUrl.isNullOrBlank()) {
            livePreviewFrameStore?.get(videoUrl)
        } else {
            null
        }
        if (cachedLiveFrame != null) {
            // A frame came from the focused live preview. It is safe to reuse
            // it without opening another stream for this card.
            Glide.with(imageView).clear(imageView)
            imageView.setImageBitmap(cachedLiveFrame)
        } else if (!movie.isLive && previewFrameManager != null && !videoUrl.isNullOrBlank()) {
            // Finite videos retain their existing on-demand frame extraction.
            Glide.with(imageView).clear(imageView)
            imageView.setImageDrawable(mDefaultCardImage)
            previewFrameManager.getPreviewFrame(videoUrl) { bitmap: Bitmap? ->
                if (imageView.tag != requestKey) return@getPreviewFrame
                if (bitmap != null) {
                    Glide.with(imageView).clear(imageView)
                    imageView.setImageBitmap(bitmap)
                } else {
                    loadCardImage(movie, imageView)
                }
            }
        } else {
            loadCardImage(movie, imageView)
        }
    }

    private fun loadCardImage(movie: Movie, imageView: android.widget.ImageView) {
        val fallback = if (movie.isLive) mLiveCardImage else mDefaultCardImage
        Glide.with(imageView).clear(imageView)
        imageView.setImageDrawable(fallback)
        if (movie.cardImageUrl.isNullOrBlank()) return
        Glide.with(imageView.context)
            .load(movie.cardImageUrl)
            .centerCrop()
            .fallback(fallback)
            .error(fallback)
            .into(imageView)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        Log.d(TAG, "onUnbindViewHolder")
        val holder = viewHolder as CardViewHolder
        holder.setCardFocusChangedListener(null)
        holder.setCardVisibilityChangedListener(null)
        onCardUnbound?.invoke(holder)
        holder.resetPreviewLayer()
        holder.clearCardBinding()
        holder.resetFocusVisual()
        val cardView = holder.cardView
        // Remove references to images so that the garbage collector can free up memory
        cardView.mainImageView?.let { imageView ->
            Glide.with(cardView).clear(imageView)
            imageView.tag = null
        }
        cardView.badgeImage = null
        cardView.mainImage = null
        cardView.setOnKeyListener(null)
    }

    /** Holder used by the TV preview controller to identify and reset cards safely. */
    class CardViewHolder internal constructor(
        internal val cardView: PreviewCardView
    ) : Presenter.ViewHolder(cardView) {
        val previewTextureView: TextureView?
            get() = cardView.previewTextureView

        fun ensurePreviewTexture(): TextureView = cardView.ensurePreviewTexture()

        fun showPreviewFrame() = cardView.showPreviewFrame()

        fun hidePreviewFrame() = cardView.hidePreviewFrame()

        fun resetPreviewLayer() = cardView.resetPreviewLayer()

        internal fun bindCard(requestKey: String) {
            boundRequestKey = requestKey
        }

        internal fun clearCardBinding() {
            boundRequestKey = null
        }

        internal fun isBoundTo(requestKey: String): Boolean {
            return boundRequestKey == requestKey
        }

        internal fun showCapturedPreviewFrame(requestKey: String, bitmap: Bitmap): Boolean {
            if (!isBoundTo(requestKey)) return false
            cardView.showCapturedPreviewFrame(bitmap)
            return true
        }

        internal fun resetPreviewLayerIfBound(requestKey: String): Boolean {
            if (!isBoundTo(requestKey)) return false
            cardView.resetPreviewLayer()
            return true
        }

        fun setCardFocusChangedListener(listener: ((Boolean) -> Unit)?) {
            cardView.setCardFocusChangedListener(listener)
        }

        fun setCardVisibilityChangedListener(listener: ((Boolean) -> Unit)?) {
            cardView.setCardVisibilityChangedListener(listener)
        }

        internal fun resetFocusVisual() = cardView.resetFocusVisual()

        internal fun refreshFocusVisual(animate: Boolean) = cardView.refreshFocusVisual(animate)

        internal fun isActuallyVisible(): Boolean = cardView.isActuallyVisible()

        internal fun isFocused(): Boolean = cardView.hasFocus()

        internal fun showCapturedPreviewFrameIfVisibleAndUnfocused(
            requestKey: String,
            bitmap: Bitmap
        ): Boolean {
            if (!isBoundTo(requestKey) || isFocused() || !isActuallyVisible()) return false
            cardView.showCapturedPreviewFrame(bitmap)
            return true
        }

        private var boundRequestKey: String? = null
    }

    /**
     * ImageCardView with a surface restricted to the main image rectangle.
     * The main image covers the live surface until the player reports its
     * first frame, while the TextureView itself remains renderable.
     */
    internal open class PreviewCardView(
        context: android.content.Context,
        private val cardMetrics: TvCardMetrics
    ) : ImageCardView(context) {
        var previewTextureView: TextureView? = null
            private set
        private var cardFocusChangedListener: ((Boolean) -> Unit)? = null
        private var cardVisibilityChangedListener: ((Boolean) -> Unit)? = null
        private var previewFrameShown = false
        private val visibilityObserver = ViewTreeObserver.OnGlobalLayoutListener {
            dispatchVisibilityChanged()
        }
        private val scrollObserver = ViewTreeObserver.OnScrollChangedListener {
            dispatchVisibilityChanged()
        }

        fun setCardContentText(text: CharSequence?) {
            contentText = text
            findViewById<View>(androidx.leanback.R.id.content_text)?.visibility =
                if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        fun resetFocusVisual() {
            animate().cancel()
            foreground = null
            scaleX = 1f
            scaleY = 1f
            elevation = CARD_ELEVATION_DP * resources.displayMetrics.density
        }

        fun refreshFocusVisual(animate: Boolean) {
            val focused = hasFocus()
            foreground = if (focused) {
                ContextCompat.getDrawable(context, R.drawable.tv_card_focus_foreground)
            } else {
                null
            }
            val targetScale = if (focused) FOCUS_SCALE else 1f
            val targetElevation = if (focused) {
                FOCUS_ELEVATION_DP * resources.displayMetrics.density
            } else {
                CARD_ELEVATION_DP * resources.displayMetrics.density
            }
            elevation = targetElevation
            animate().cancel()
            if (!animate) {
                scaleX = targetScale
                scaleY = targetScale
                return
            }
            animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(FOCUS_ANIMATION_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        fun setCardFocusChangedListener(listener: ((Boolean) -> Unit)?) {
            cardFocusChangedListener = listener
        }

        fun setCardVisibilityChangedListener(listener: ((Boolean) -> Unit)?) {
            cardVisibilityChangedListener = listener
            listener?.invoke(isActuallyVisible())
        }

        fun isActuallyVisible(): Boolean {
            if (!isAttachedToWindow || !isShown || width <= 0 || height <= 0) return false
            val visibleRect = android.graphics.Rect()
            return getGlobalVisibleRect(visibleRect) &&
                visibleRect.width() > 0 && visibleRect.height() > 0
        }

        private fun dispatchVisibilityChanged() {
            cardVisibilityChangedListener?.invoke(isActuallyVisible())
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnGlobalLayoutListener(visibilityObserver)
            viewTreeObserver.addOnScrollChangedListener(scrollObserver)
            dispatchVisibilityChanged()
        }

        override fun onDetachedFromWindow() {
            if (viewTreeObserver.isAlive) {
                viewTreeObserver.removeOnGlobalLayoutListener(visibilityObserver)
                viewTreeObserver.removeOnScrollChangedListener(scrollObserver)
            }
            cardVisibilityChangedListener?.invoke(false)
            super.onDetachedFromWindow()
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            dispatchVisibilityChanged()
        }

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: android.graphics.Rect?
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            refreshFocusVisual(animate = true)
            cardFocusChangedListener?.invoke(gainFocus)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            // Measure the display in a second phase so BaseCardView excludes it from content stacking.
            previewTextureView?.let { textureView ->
                textureView.measure(
                    MeasureSpec.makeMeasureSpec(cardMetrics.imageWidthPx, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cardMetrics.imageHeightPx, MeasureSpec.EXACTLY)
                )
                textureView.layout(
                    paddingLeft,
                    paddingTop,
                    paddingLeft + cardMetrics.imageWidthPx,
                    paddingTop + cardMetrics.imageHeightPx
                )
            }
            if (!previewFrameShown) {
                mainImageView?.alpha = if (previewTextureView?.visibility == View.VISIBLE) {
                    PREVIEW_COVER_ALPHA
                } else {
                    1f
                }
                mainImageView?.bringToFront()
            }
            dispatchVisibilityChanged()
        }

        fun ensurePreviewTexture(): TextureView {
            previewTextureView?.let { textureView ->
                textureView.visibility = View.VISIBLE
                textureView.alpha = 1f
                if (previewFrameShown) {
                    textureView.bringToFront()
                } else {
                    mainImageView?.alpha = PREVIEW_COVER_ALPHA
                    mainImageView?.bringToFront()
                }
                return textureView
            }

            return TextureView(context).also { textureView ->
                textureView.layoutParams = BaseCardView.LayoutParams(cardMetrics.imageWidthPx, 0)
                textureView.isFocusable = false
                textureView.isFocusableInTouchMode = false
                textureView.isClickable = false
                textureView.isLongClickable = false
                textureView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                textureView.visibility = View.VISIBLE
                textureView.alpha = 1f
                addView(textureView)
                previewTextureView = textureView
                // Keep the surface alive and renderable, but cover it until
                // the player confirms that a real frame has arrived.
                mainImageView?.alpha = PREVIEW_COVER_ALPHA
                mainImageView?.bringToFront()
            }
        }

        fun showPreviewFrame() {
            previewFrameShown = true
            previewTextureView?.let { textureView ->
                textureView.visibility = View.VISIBLE
                textureView.alpha = 1f
                textureView.bringToFront()
            }
            // Keep the main image in the card's layout so Leanback does not
            // move the title area up when the artwork is replaced.
            mainImageView?.alpha = 0f
            mainImageView?.visibility = View.VISIBLE
        }

        fun hidePreviewFrame() {
            previewFrameShown = false
            previewTextureView?.let { textureView ->
                textureView.alpha = 1f
                textureView.visibility = View.GONE
            }
            mainImageView?.alpha = 1f
            mainImageView?.visibility = View.VISIBLE
            mainImageView?.bringToFront()
        }

        fun showCapturedPreviewFrame(bitmap: Bitmap) {
            resetPreviewLayer()
            mainImageView?.let { imageView ->
                Glide.with(imageView).clear(imageView)
                imageView.setImageBitmap(bitmap)
            }
        }

        fun resetPreviewLayer() {
            previewFrameShown = false
            previewTextureView?.let(::removeView)
            previewTextureView = null
            mainImageView?.alpha = 1f
            mainImageView?.visibility = View.VISIBLE
            mainImageView?.bringToFront()
        }
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = ContextCompat.getColor(view.context, R.color.card_info_background)
        // Keep the selected info panel dark; focus is represented by the foreground drawable.
        view.setBackgroundColor(color)
        view.setInfoAreaBackgroundColor(color)
    }

    companion object {
        private val TAG = "CardPresenter"

        private const val FOCUS_SCALE = 1.05f
        private const val FOCUS_ANIMATION_DURATION_MS = 140L
        private const val CARD_ELEVATION_DP = 2f
        private const val FOCUS_ELEVATION_DP = 10f
        // A nearly opaque cover keeps the TextureView in Honor's composition
        // path while leaving the user-visible card image effectively unchanged.
        private const val PREVIEW_COVER_ALPHA = 0.99f
    }
}
