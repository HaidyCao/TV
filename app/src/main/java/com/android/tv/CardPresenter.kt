package com.android.tv

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.ViewGroup

import com.bumptech.glide.Glide
import kotlin.properties.Delegates

/**
 * A CardPresenter is used to generate Views and bind Objects to them on demand.
 * It contains an ImageCardView.
 */
class CardPresenter : Presenter() {
    private var mDefaultCardImage: Drawable? = null
    private var sSelectedBackgroundColor: Int by Delegates.notNull()
    private var sDefaultBackgroundColor: Int by Delegates.notNull()

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        Log.d(TAG.d, "onCreateViewHolder")

        sDefaultBackgroundColor = ContextCompat.getColor(parent.context, R.color.default_background)
        sSelectedBackgroundColor =
            ContextCompat.getColor(parent.context, R.color.selected_background)
        mDefaultCardImage = ContextCompat.getDrawable(parent.context, R.drawable.movie)

        val cardView = object : ImageCardView(parent.context) {
            override fun setSelected(selected: Boolean) {
                updateCardBackgroundColor(this, selected)
                super.setSelected(selected)
            }
        }

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        updateCardBackgroundColor(cardView, false)
        return Presenter.ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        if (item == null) return
        val movie = item as Movie
        val cardView = viewHolder.view as ImageCardView

        Log.d(TAG.d, "onBindViewHolder: ${movie.title}")
        cardView.titleText = movie.title
        cardView.contentText = movie.studio
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

        val imageView = cardView.mainImageView
        if (imageView != null) {
            // 优先检查预览缓存
            val videoUrl = movie.videoUrl
            if (videoUrl != null) {
                val cachedPreview = PreviewGenerator.previewCache.get(videoUrl)
                if (cachedPreview != null) {
                    Log.d(TAG.d, "Using cached preview for: ${movie.title}, cache size=${PreviewGenerator.previewCache.size()}")
                    // 先清除 Glide 之前的加载
                    Glide.with(viewHolder.view.context).clear(imageView)
                    imageView.setImageBitmap(cachedPreview)
                    return
                } else {
                    Log.d(TAG.d, "No cached preview for: ${movie.title}, videoUrl=${videoUrl.take(50)}..., cache size=${PreviewGenerator.previewCache.size()}")
                }
            }

            // 没有缓存，使用 Glide 加载默认卡片图片
            Glide.with(viewHolder.view.context)
                .load(movie.cardImageUrl)
                .centerCrop()
                .error(mDefaultCardImage)
                .into(imageView)
        }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        Log.d(TAG.d, "onUnbindViewHolder")
        val cardView = viewHolder.view as ImageCardView
        // Remove references to badge only
        // Don't clear mainImage as it's reused in onBindViewHolder
        cardView.badgeImage = null
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = if (selected) sSelectedBackgroundColor else sDefaultBackgroundColor
        // Both background colors should be set because the view"s background is temporarily visible
        // during animations.
        view.setBackgroundColor(color)
        view.setInfoAreaBackgroundColor(color)
    }

    companion object {
        private class TAG {
            companion object {
                const val d = "CardPresenter"
            }
        }

        private val CARD_WIDTH = 313
        private val CARD_HEIGHT = 176
    }
}
