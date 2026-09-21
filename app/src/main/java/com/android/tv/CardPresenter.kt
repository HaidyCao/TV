package com.android.tv

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.Gravity
import androidx.leanback.widget.BaseCardView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import kotlin.properties.Delegates

/**
 * A CardPresenter is used to generate Views and bind Objects to them on demand.
 * It contains an ImageCardView.
 */
class CardPresenter(
    private val previewFrameManager: PreviewFrameManager? = null,
    private val isFavorite: (Movie) -> Boolean = { false },
    private val onFavoriteToggle: ((Movie) -> Unit)? = null,
    private val onCardUnbound: ((CardViewHolder) -> Unit)? = null,
    private val onCardFocusChanged: ((Movie, CardViewHolder, Boolean) -> Unit)? = null
) : Presenter() {
    private var mDefaultCardImage: Drawable? = null
    private var mLiveCardImage: Drawable? = null
    private var sSelectedBackgroundColor: Int by Delegates.notNull()
    private var sDefaultBackgroundColor: Int by Delegates.notNull()

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        Log.d(TAG, "onCreateViewHolder")

        sDefaultBackgroundColor = ContextCompat.getColor(parent.context, R.color.default_background)
        sSelectedBackgroundColor =
            ContextCompat.getColor(parent.context, R.color.selected_background)
        mDefaultCardImage = ContextCompat.getDrawable(parent.context, R.drawable.movie)
        mLiveCardImage = ContextCompat.getDrawable(parent.context, R.drawable.channel_placeholder)

        val cardView = object : PreviewCardView(parent.context) {
            override fun setSelected(selected: Boolean) {
                updateCardBackgroundColor(this, selected)
                super.setSelected(selected)
            }
        }

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        updateCardBackgroundColor(cardView, false)
        return CardViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val holder = viewHolder as CardViewHolder
        // Leanback may reuse a holder without a visible transition. Detach any
        // preview surface before painting the new channel into that holder.
        holder.setCardFocusChangedListener(null)
        onCardUnbound?.invoke(holder)
        holder.resetPreviewLayer()
        if (item == null) return
        val movie = item as Movie
        val cardView = holder.cardView

        Log.d(TAG, "onBindViewHolder")
        cardView.titleText = movie.title
        cardView.contentText = movie.studio
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
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
        
        val imageView = cardView.mainImageView ?: return
        val requestKey = "${movie.id}:${movie.videoUrl.orEmpty()}"
        imageView.tag = requestKey
        imageView.visibility = View.VISIBLE

        // IPTV/live cards must never open their stream just to paint a thumbnail.
        // They use a playlist-provided logo or local fallback artwork instead.
        val videoUrl = movie.videoUrl
        if (!movie.isLive && previewFrameManager != null && !videoUrl.isNullOrBlank()) {
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
        onCardUnbound?.invoke(holder)
        holder.resetPreviewLayer()
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

        fun setCardFocusChangedListener(listener: ((Boolean) -> Unit)?) {
            cardView.setCardFocusChangedListener(listener)
        }
    }

    /**
     * ImageCardView with a surface restricted to the main image rectangle.
     * The surface is transparent until the player reports its first frame.
     */
    internal open class PreviewCardView(context: android.content.Context) : ImageCardView(context) {
        var previewTextureView: TextureView? = null
            private set
        private var cardFocusChangedListener: ((Boolean) -> Unit)? = null

        fun setCardFocusChangedListener(listener: ((Boolean) -> Unit)?) {
            cardFocusChangedListener = listener
        }

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: android.graphics.Rect?
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            cardFocusChangedListener?.invoke(gainFocus)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            previewTextureView?.measure(
                MeasureSpec.makeMeasureSpec(CARD_WIDTH, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(CARD_HEIGHT, MeasureSpec.EXACTLY)
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            previewTextureView?.layout(
                paddingLeft,
                paddingTop,
                paddingLeft + CARD_WIDTH,
                paddingTop + CARD_HEIGHT
            )
        }

        fun ensurePreviewTexture(): TextureView {
            previewTextureView?.let { textureView ->
                textureView.visibility = View.VISIBLE
                textureView.alpha = 0f
                return textureView
            }

            return TextureView(context).also { textureView ->
                // Mark the preview overlay as EXTRA so Leanback does not include it in card height.
                textureView.layoutParams = BaseCardView.LayoutParams(CARD_WIDTH, CARD_HEIGHT).apply {
                    gravity = Gravity.TOP or Gravity.START
                    viewType = BaseCardView.LayoutParams.VIEW_TYPE_EXTRA
                }
                textureView.isFocusable = false
                textureView.isFocusableInTouchMode = false
                textureView.isClickable = false
                textureView.isLongClickable = false
                textureView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                textureView.visibility = View.VISIBLE
                textureView.alpha = 0f
                addView(textureView)
                previewTextureView = textureView
            }
        }

        fun showPreviewFrame() {
            previewTextureView?.let { textureView ->
                textureView.visibility = View.VISIBLE
                textureView.alpha = 1f
            }
            // Keep the main image in the card's layout so Leanback does not
            // move the title area up when the artwork is replaced.
            mainImageView?.alpha = 0f
            mainImageView?.visibility = View.VISIBLE
        }

        fun hidePreviewFrame() {
            previewTextureView?.let { textureView ->
                textureView.alpha = 0f
                textureView.visibility = View.GONE
            }
            mainImageView?.alpha = 1f
            mainImageView?.visibility = View.VISIBLE
        }

        fun resetPreviewLayer() {
            previewTextureView?.let(::removeView)
            previewTextureView = null
            mainImageView?.alpha = 1f
            mainImageView?.visibility = View.VISIBLE
        }
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = if (selected) sSelectedBackgroundColor else sDefaultBackgroundColor
        // Both background colors should be set because the view"s background is temporarily visible
        // during animations.
        view.setBackgroundColor(color)
        view.setInfoAreaBackgroundColor(color)
    }

    companion object {
        private val TAG = "CardPresenter"

        private val CARD_WIDTH = 313
        private val CARD_HEIGHT = 176
    }
}
