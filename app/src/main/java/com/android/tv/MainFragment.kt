package com.android.tv

import java.util.Collections
import java.util.Timer
import java.util.TimerTask

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.view.WindowMetrics
import android.view.WindowInsets

import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Loads a grid of cards with movies to browse.
 */
class MainFragment : BrowseSupportFragment() {

    private val mHandler = Handler(Looper.myLooper()!!)
    private lateinit var mBackgroundManager: BackgroundManager
    private var mDefaultBackground: Drawable? = null
    private lateinit var mMetrics: DisplayMetrics
    private var mBackgroundTimer: Timer? = null
    private var mBackgroundUri: String? = null

    private val NUM_ROWS = 6
    private val NUM_COLS = 15

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        Log.i(TAG, "onCreate")

        prepareBackgroundManager()
        setupUIElements()
        loadRows()
        setupEventListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: " + mBackgroundTimer?.toString())
        mBackgroundTimer?.cancel()
    }

    private fun prepareBackgroundManager() {
        mBackgroundManager = BackgroundManager.getInstance(activity)
        mBackgroundManager.attach(requireActivity().window)
        mDefaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)

        // 使用 WindowMetrics 获取屏幕尺寸，解决 DisplayMetrics 弃用问题
        val windowMetrics: WindowMetrics = requireActivity().windowManager.currentWindowMetrics
        mMetrics = DisplayMetrics()
        windowMetrics.bounds.width().also { mMetrics.widthPixels = it }
        windowMetrics.bounds.height().also { mMetrics.heightPixels = it }
    }

    private fun setupUIElements() {
        title = getString(R.string.browse_title)
        // over title
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        // set fastLane (or headers) background color
        brandColor = ContextCompat.getColor(requireContext(), R.color.fastlane_background)
        // set search icon color
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.search_opaque)
    }

    private fun loadRows() {
        lifecycleScope.launch {
            val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
            val cardPresenter = CardPresenter()

            // 1. 加载电视直播频道
            val tvGroups = TvDataManager.fetchTvChannels(requireContext())
            tvGroups.forEach { (category, channels) ->
                val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                channels.forEach { listRowAdapter.add(it) }
                val header = HeaderItem(rowsAdapter.size().toLong(), category)
                rowsAdapter.add(ListRow(header, listRowAdapter))
            }

            // 2. 加载原有的示例电影数据 (可选，放在直播后面)
            val list = MovieList.list
            if (list.isNotEmpty()) {
                val movieRowAdapter = ArrayObjectAdapter(cardPresenter)
                for (j in 0 until NUM_COLS) {
                    movieRowAdapter.add(list[j % 5])
                }
                val header = HeaderItem(rowsAdapter.size().toLong(), "经典点播")
                rowsAdapter.add(ListRow(header, movieRowAdapter))
            }

            // 3. 设置界面选项
            val gridHeader = HeaderItem(rowsAdapter.size().toLong(), "设置")
            val mGridPresenter = GridItemPresenter()
            val gridRowAdapter = ArrayObjectAdapter(mGridPresenter)
            gridRowAdapter.add(resources.getString(R.string.grid_view))
            gridRowAdapter.add(getString(R.string.error_fragment))
            gridRowAdapter.add(resources.getString(R.string.personal_settings))
            rowsAdapter.add(ListRow(gridHeader, gridRowAdapter))

            adapter = rowsAdapter
        }
    }

    private fun setupEventListeners() {
        setOnSearchClickedListener {
            Toast.makeText(requireActivity(), "Implement your own in-app search", Toast.LENGTH_LONG).show()
        }

        onItemViewClickedListener = ItemViewClickedListener()
        onItemViewSelectedListener = ItemViewSelectedListener()
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            if (item is Movie) {
                Log.d(TAG, "Item: " + item.toString())
                val intent = if (item.studio == "直播频道") {
                    Intent(requireActivity(), PlaybackActivity::class.java)
                } else {
                    Intent(requireActivity(), DetailsActivity::class.java)
                }
                intent.putExtra(DetailsActivity.MOVIE, item)

                if (item.studio != "直播频道") {
                    val imageView = (itemViewHolder.view as ImageCardView).mainImageView
                    if (imageView != null) {
                        val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(
                            requireActivity(),
                            imageView,
                            DetailsActivity.SHARED_ELEMENT_NAME
                        ).toBundle()
                        startActivity(intent, bundle)
                    } else {
                        startActivity(intent)
                    }
                } else {
                    startActivity(intent)
                }
            } else if (item is String) {
                if (item == resources.getString(R.string.personal_settings)) {
                    val intent = Intent(requireActivity(), SettingsActivity::class.java)
                    startActivity(intent)
                } else if (item == getString(R.string.error_fragment)) {
                    val intent = Intent(requireActivity(), BrowseErrorActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(requireActivity(), item, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?, item: Any?,
            rowViewHolder: RowPresenter.ViewHolder, row: Row
        ) {
            if (item is Movie) {
                mBackgroundUri = item.backgroundImageUrl
                startBackgroundTimer()
            }
        }
    }

    private fun updateBackground(uri: String?) {
        val width = mMetrics.widthPixels
        val height = mMetrics.heightPixels
        Glide.with(requireActivity())
            .load(uri)
            .centerCrop()
            .error(mDefaultBackground)
            .into(object : CustomTarget<Drawable>(width, height) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    mBackgroundManager.drawable = resource
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Do nothing
                }
            })
        mBackgroundTimer?.cancel()
    }

    private fun startBackgroundTimer() {
        mBackgroundTimer?.cancel()
        mBackgroundTimer = Timer()
        mBackgroundTimer?.schedule(UpdateBackgroundTask(), BACKGROUND_UPDATE_DELAY.toLong())
    }

    private inner class UpdateBackgroundTask : TimerTask() {
        override fun run() {
            mHandler.post { updateBackground(mBackgroundUri) }
        }
    }

    private inner class GridItemPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
            val view = TextView(parent.context)
            view.layoutParams = ViewGroup.LayoutParams(GRID_ITEM_WIDTH, GRID_ITEM_HEIGHT)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setBackgroundColor(ContextCompat.getColor(parent.context, R.color.default_background))
            view.setTextColor(Color.WHITE)
            view.gravity = Gravity.CENTER
            return Presenter.ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
            if (item == null) return
            (viewHolder.view as TextView).text = item as String
        }

        override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {}
    }

    companion object {
        private val TAG = "MainFragment"
        private const val BACKGROUND_UPDATE_DELAY = 300
        private const val GRID_ITEM_WIDTH = 200
        private const val GRID_ITEM_HEIGHT = 200
    }
}
