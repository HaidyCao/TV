package com.android.tv

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
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import jp.wasabeef.glide.transformations.GrayscaleTransformation
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import androidx.cardview.widget.CardView
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.VerticalGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import jp.wasabeef.glide.transformations.BlurTransformation

/**
 * Loads a grid of cards with movies to browse.
 */
@UnstableApi
class MainFragment : BrowseSupportFragment() {

    private val mHandler = Handler(Looper.getMainLooper())
    private lateinit var mBackgroundManager: BackgroundManager
    private var mDefaultBackground: Drawable? = null
    private lateinit var mMetrics: DisplayMetrics

    private var mSelectedItem: Movie? = null
    private var mSelectedViewHolder: Presenter.ViewHolder? = null

    // 行适配器，用于访问和更新数据
    private var rowsAdapter: ArrayObjectAdapter? = null

    // PreviewGenerator 相关
    private var isPreviewGeneratorInitialized = false
    private var previewScheduleRunnable: Runnable? = null
    private var livePreviewRunnable: Runnable? = null
    private val PREVIEW_DEBOUNCE_MS = 100L
    private val LIVE_PREVIEW_DELAY_MS = 500L

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        Log.i(TAG.d, "onActivityCreated: savedInstanceState=${savedInstanceState != null}")

        prepareBackgroundManager()
        setupUIElements()
        loadRows()
        setupEventListeners()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG.d, "onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG.d, "onResume")

        updateAllPreview()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG.d, "onPause")
        livePreviewRunnable?.let { mHandler.removeCallbacks(it) }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG.d, "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG.d, "onDestroy: ")
        previewScheduleRunnable?.let { mHandler.removeCallbacks(it) }
        livePreviewRunnable?.let { mHandler.removeCallbacks(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG.d, "onDestroyView")
        // 清理预览队列
        PreviewGenerator.clearQueue()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.d(TAG.d, "onSaveInstanceState")
    }

    private fun updateAllPreview() {
        val rowsFragment = rowsSupportFragment ?: return
        val vGridView = rowsFragment.verticalGridView ?: return
        // 1. 遍历当前屏幕上可见的“行”视图
        for (i in 0 until vGridView.childCount) {
            val rowView = vGridView.getChildAt(i)
            val rowPos = vGridView.getChildAdapterPosition(rowView)
            if (rowPos == -1) continue
            Log.d(TAG.d, "onPreviewReady: rowPos: $rowPos")

            val listRow = adapter.get(rowPos) as? ListRow ?: continue
            val rowAdapter = listRow.adapter as? ArrayObjectAdapter ?: continue

            val hGridView = rowView.findViewById<HorizontalGridView>(androidx.leanback.R.id.row_content) ?: return

            for (n in 0 until hGridView.childCount) {
                val cardView = hGridView.getChildAt(n)
                val cardPos = hGridView.getChildAdapterPosition(cardView)
                if (cardPos == -1) continue
                Log.d(TAG.d, "onPreviewReady: cardPos: $cardPos")

                val v = rowAdapter.get(cardPos)
                if (v is Movie) {
                    Log.d(TAG.d, "onPreviewReady: cardPos: $cardPos; url: ${v.videoUrl}; cardViewType: ${cardView.javaClass}")
                    val bitmap = PreviewGenerator.getPreviewFromCache(v.videoUrl!!)
                    if (bitmap != null) {
                        val mainImageView = cardView.findViewById<ImageView>(androidx.leanback.R.id.main_image)
                        mainImageView.setImageBitmap(bitmap)
                    }
                }
            }
        }

        val selectedItem = mSelectedItem ?: return
        val selectedViewHolder = mSelectedViewHolder ?: return
        onItemSelected(selectedItem, selectedViewHolder)
    }

    private fun prepareBackgroundManager() {
        mBackgroundManager = BackgroundManager.getInstance(activity)
        mBackgroundManager.attach(requireActivity().window)
        mDefaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)

        mMetrics = DisplayMetrics()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics = requireActivity().windowManager.currentWindowMetrics
            windowMetrics.bounds.width().also { mMetrics.widthPixels = it }
            windowMetrics.bounds.height().also { mMetrics.heightPixels = it }
        } else {
            @Suppress("DEPRECATION")
            requireActivity().windowManager.defaultDisplay.getMetrics(mMetrics)
        }
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
            val adapter = ArrayObjectAdapter(ListRowPresenter())
            val cardPresenter = CardPresenter()

            // 初始化 PreviewGenerator
            if (!isPreviewGeneratorInitialized) {
                isPreviewGeneratorInitialized = true
                (requireActivity() as? MainActivity)?.initializePreviewGenerator { title, url, bitmap ->
                    onPreviewReady(title, url, bitmap)
                }
            }

            // 1. 加载电视直播频道
            val tvGroups = TvDataManager.fetchTvChannels(requireContext())
            tvGroups.forEach { (category, channels) ->
                val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                channels.forEach { listRowAdapter.add(it) }
                val header = HeaderItem(adapter.size().toLong(), category)
                adapter.add(ListRow(header, listRowAdapter))
            }

            // 2. 设置界面选项
            val gridHeader = HeaderItem(adapter.size().toLong(), "设置")
            val mGridPresenter = GridItemPresenter()
            val gridRowAdapter = ArrayObjectAdapter(mGridPresenter)
            gridRowAdapter.add(resources.getString(R.string.grid_view))
            gridRowAdapter.add(getString(R.string.error_fragment))
            gridRowAdapter.add(resources.getString(R.string.personal_settings))
            adapter.add(ListRow(gridHeader, gridRowAdapter))

            this@MainFragment.rowsAdapter = adapter
            this@MainFragment.adapter = adapter
        }
    }

    private fun setupEventListeners() {
        setOnSearchClickedListener {
            // 启动搜索界面
            val searchFragment = SearchFragment()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, searchFragment)
                .addToBackStack(null)
                .commit()
        }

        onItemViewClickedListener = ItemViewClickedListener()
        onItemViewSelectedListener = ItemViewSelectedListener()
    }

    /**
     * 预览完成回调
     */
    private fun onPreviewReady(title: String, videoUrl: String, bitmap: Bitmap) {
        Log.d(TAG.d, "onPreviewReady: Preview ready: title = ${title}; url = $videoUrl, selected item is ${mSelectedItem?.videoUrl}")
        // 只有当完成的预览是当前选中的项目时，才更新背景
        if (mSelectedItem?.videoUrl == videoUrl) {
            updateBackground(bitmap)
        }

        val rowsFragment = rowsSupportFragment ?: return
        val vGridView = rowsFragment.verticalGridView ?: return
        // 1. 遍历当前屏幕上可见的“行”视图
        for (i in 0 until vGridView.childCount) {
            val rowView = vGridView.getChildAt(i)
            val rowPos = vGridView.getChildAdapterPosition(rowView)
            if (rowPos == -1) continue
            Log.d(TAG.d, "onPreviewReady: rowPos: $rowPos")

            val listRow = adapter.get(rowPos) as? ListRow ?: continue
            val rowAdapter = listRow.adapter as? ArrayObjectAdapter ?: continue

            val hGridView = rowView.findViewById<HorizontalGridView>(androidx.leanback.R.id.row_content) ?: return

            for (n in 0 until hGridView.childCount) {
                val cardView = hGridView.getChildAt(n)
                val cardPos = hGridView.getChildAdapterPosition(cardView)
                if (cardPos == -1) continue
                Log.d(TAG.d, "onPreviewReady: cardPos: $cardPos")

                val v = rowAdapter.get(cardPos)
                if (v is Movie) {
                    Log.d(TAG.d, "onPreviewReady: cardPos: $cardPos; url: ${v.videoUrl}; cardViewType: ${cardView.javaClass}")
                    if (v.videoUrl == videoUrl) {
                        val mainImageView = cardView.findViewById<ImageView>(androidx.leanback.R.id.main_image)
                        if (mainImageView != null) {
                            Log.d(TAG.d, "onPreviewReady: find target position: ${rowPos}-${cardPos}")
                            mainImageView.setImageBitmap(bitmap)
                            return
                        }
                    }
                }
            }
        }
    }

    /**
     * 调度预览请求
     * 使用防抖机制避免频繁调用
     */
    private fun schedulePreviewRequests(item: Movie) {
        previewScheduleRunnable?.let { mHandler.removeCallbacks(it) }
        previewScheduleRunnable = Runnable {
            updateVisibleItems(item)
        }
        mHandler.postDelayed(previewScheduleRunnable!!, PREVIEW_DEBOUNCE_MS)
    }

    /**
     * 调度实时预览播放
     * 选中后 500ms 触发
     */
    private fun scheduleLivePreview(item: Movie, itemViewHolder: Presenter.ViewHolder?) {
        livePreviewRunnable?.let { mHandler.removeCallbacks(it) }
        // 立即停止上一个播放
        PreviewGenerator.stopLivePreview()

        val vh = itemViewHolder as? CardPresenter.CardViewHolder ?: return
        vh.previewTextureView.tag = false

        livePreviewRunnable = Runnable {
            // 确保仍然是选中项
            livePreviewRunnable = null
            if (mSelectedItem == item) {
                val mainImage = vh.cardView.mainImageView
                if (mainImage != null) {
                    var width = -1
                    var height = -1
                    if (mainImage.tag == null) {
                        mainImage.tag = Pair(mainImage.width, mainImage.height)
                        width = mainImage.width
                        height = mainImage.height
                    } else {
                        val tag = mainImage.tag as Pair<*, *>
                        width = tag.first as Int
                        height = tag.second as Int
                    }

                    vh.previewTextureView.layoutParams.width = width
                    vh.previewTextureView.layoutParams.height = height

                    vh.previewTextureView.tag = true

                    mainImage.layoutParams.width = 0
                    mainImage.layoutParams.height = 0

                    Log.d("TAG", "scheduleLivePreview: mainImage GONE")
                    PreviewGenerator.startLivePreview(item.videoUrl!!, vh.previewTextureView, mainImage)
                }
            }
        }
        mHandler.postDelayed(livePreviewRunnable!!, LIVE_PREVIEW_DELAY_MS)
    }

    private fun updateVisibleItems(item: Movie) {
        val adapter = rowsAdapter ?: return

        // 清空当前预览队列
        PreviewGenerator.clearQueue()

        // 获取当前可见的行和列
        val selectedPosition = selectedPosition
        if (selectedPosition < 0) return

        Log.d(TAG.d, "requestPreview: ${item.title}")
        PreviewGenerator.requestPreview(item.title!!,item.videoUrl!!, 0, CARD_WIDTH, CARD_HEIGHT)

        val rowsFragment = rowsSupportFragment ?: return
        val vGridView = rowsFragment.verticalGridView ?: return
        // 1. 遍历当前屏幕上可见的“行”视图
        for (i in 0 until vGridView.childCount) {
            val rowView = vGridView.getChildAt(i)
            val rowPos = vGridView.getChildAdapterPosition(rowView)
            if (rowPos == -1) continue

            val isSelectedRow = rowPos == selectedPosition

            val listRow = adapter.get(rowPos) as? ListRow ?: continue
            val rowAdapter = listRow.adapter as? ArrayObjectAdapter ?: continue

            val hGridView = rowView.findViewById<HorizontalGridView>(androidx.leanback.R.id.row_content) ?: return
            val visiblePositions = ArrayList<Int>()

            for (n in 0 until hGridView.childCount) {
                val cardView = hGridView.getChildAt(n)
                val cardPos = hGridView.getChildAdapterPosition(cardView)
                if (cardPos == -1) continue

                visiblePositions.add(cardPos)
            }

            for (n in 0 until rowAdapter.size()) {
                val v = rowAdapter.get(n)
                if (v is Movie) {
                    if (v == item) {
                        continue
                    }

                    Log.d(TAG.d, "requestPreview: ${v.title}")
                    if (visiblePositions.contains(n)) {
                        Log.d(TAG.d, "visiblePositions contains: ${v.title}")
                        val priority = if (isSelectedRow) 1 else 2
                        PreviewGenerator.requestPreview(v.title!!,v.videoUrl!!, priority, CARD_WIDTH, CARD_HEIGHT)
                        continue
                    }
                }
            }
        }
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            if (item is Movie) {
                Log.d(TAG.d, "Item: " + item.toString())
                val intent = if (item.studio == "直播频道") {
                    Intent(requireActivity(), PlaybackActivity::class.java)
                } else {
                    Intent(requireActivity(), DetailsActivity::class.java)
                }
                intent.putExtra(DetailsActivity.MOVIE, item)

                PreviewGenerator.stopLivePreview()

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
                PreviewGenerator.stopLivePreview()
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
            if (mSelectedItem == item) {
                return
            }

            Log.d(TAG.d, "Selected: " + item)
            if (item is Movie) {
                mSelectedItem = item
                mSelectedViewHolder = itemViewHolder

                onItemSelected(item, itemViewHolder)
            }
        }
    }

    private fun onItemSelected(item: Movie, itemViewHolder: Presenter.ViewHolder?) {
        // 如果该项之前已失败多次，用户的主动选择将给予它新的机会
        PreviewGenerator.clearFailureRecord(item.videoUrl!!)

        val cachedBitmap = PreviewGenerator.getPreviewFromCache(item.videoUrl!!)
        if (cachedBitmap != null) {
            updateBackground(cachedBitmap)
        }
        // 无论如何都触发预览调度，以便在没有缓存时生成
        schedulePreviewRequests(item)
        // 500ms 后触发实时预览播放
        scheduleLivePreview(item, itemViewHolder)
    }

    private fun updateBackground(bitmap: Bitmap) {
        val width = mMetrics.widthPixels
        val height = mMetrics.heightPixels
        Glide.with(requireActivity())
            .load(bitmap)
            .apply(RequestOptions.bitmapTransform(jp.wasabeef.glide.transformations.BlurTransformation(8, 2)))
            .into(object : CustomTarget<Drawable>(width, height) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    mBackgroundManager.drawable = resource
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Do nothing
                }
            })
    }

    private class GridItemPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
            val view = TextView(parent.context)
            view.layoutParams = ViewGroup.LayoutParams(GRID_ITEM_WIDTH, GRID_ITEM_HEIGHT)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setBackgroundColor(ContextCompat.getColor(parent.context, R.color.default_background))
            view.setTextColor(Color.WHITE)
            view.gravity = Gravity.CENTER
            return ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            if (item == null) return
            (viewHolder.view as TextView).text = item as String
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
    }

    companion object {
        private class TAG {
            companion object {
                const val d = "MainFragment"
            }
        }

        private const val BACKGROUND_UPDATE_DELAY = 300
        private const val GRID_ITEM_WIDTH = 200
        private const val GRID_ITEM_HEIGHT = 200
        private const val CARD_WIDTH = 313
        private const val CARD_HEIGHT = 176
    }
}
