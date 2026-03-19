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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.graphics.Bitmap

/**
 * Loads a grid of cards with movies to browse.
 */
@UnstableApi
class MainFragment : BrowseSupportFragment() {

    private val mHandler = Handler(Looper.getMainLooper())
    private lateinit var mBackgroundManager: BackgroundManager
    private var mDefaultBackground: Drawable? = null
    private lateinit var mMetrics: DisplayMetrics
    private var mBackgroundTimer: Timer? = null
    private var mBackgroundUri: String? = null

    // 行适配器，用于访问和更新数据
    private var rowsAdapter: ArrayObjectAdapter? = null

    // PreviewGenerator 相关
    private var isPreviewGeneratorInitialized = false
    private var previewScheduleRunnable: Runnable? = null
    private val PREVIEW_DEBOUNCE_MS = 100L

    private val NUM_ROWS = 6
    private val NUM_COLS = 15

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
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG.d, "onPause")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG.d, "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG.d, "onDestroy: " + mBackgroundTimer?.toString())
        mBackgroundTimer?.cancel()
        previewScheduleRunnable?.let { mHandler.removeCallbacks(it) }
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
                (requireActivity() as? MainActivity)?.initializePreviewGenerator { url, bitmap ->
                    onPreviewReady(url, bitmap)
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
    private fun onPreviewReady(videoUrl: String, bitmap: Bitmap) {
        Log.d(TAG.d, "Preview ready: $videoUrl")
        // 刷新可见项的视图
        rowsAdapter?.let { adapter ->
            for (i in 0 until adapter.size()) {
                val row = adapter.get(i) as? Row ?: continue
                val listRow = row as? ListRow ?: continue
                val rowAdapter = listRow.adapter
                for (j in 0 until rowAdapter.size()) {
                    val item = rowAdapter.get(j)
                    if (item is Movie && item.videoUrl == videoUrl) {
                        // 通知刷新对应项
                        Log.d(TAG.d, "notifyItemChanged: ${item.title}")
                        rowAdapter.notifyItemRangeChanged(j, 1)
                        break
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
        // 移除之前的调度
        previewScheduleRunnable?.let { mHandler.removeCallbacks(it) }

        previewScheduleRunnable = Runnable {
            updateVisibleItems(item)
        }

        mHandler.postDelayed(previewScheduleRunnable!!, PREVIEW_DEBOUNCE_MS)
    }

    /**
     * 更新可见项的预览请求
     */
    private fun updateVisibleItems(item: Movie) {
        val adapter = rowsAdapter ?: return

        // 清空当前预览队列
        PreviewGenerator.clearQueue()

        // 获取当前可见的行和列
        val selectedPosition = selectedPosition
        if (selectedPosition < 0) return

        val selectedRow = adapter.get(selectedPosition) as? Row ?: return
        val listRow = selectedRow as? ListRow ?: return
        val rowAdapter = listRow.adapter

        Log.d(TAG.d, "requestPreview: ${item.title}")
        PreviewGenerator.requestPreview(item.title!!,item.videoUrl!!, 0, CARD_WIDTH, CARD_HEIGHT)

        var pos = -1;
        for (i in 0 until rowAdapter.size()) {
            val v = rowAdapter.get(i)
            if (v is Movie && v == item) {
                pos = i
                break
            }
        }

        if (pos == -1) {
            Log.d(TAG.d, "Item not found in adapter")
            return
        }

        // 相邻的位置的优先级依次降低
        for (i in (pos - 1) downTo 0) {
            Log.d(TAG.d, "pos: $pos, i: $i")
            val v = rowAdapter.get(i)
            if (v is Movie) {
                Log.d(TAG.d, "requestPreview: ${v.title}")
                PreviewGenerator.requestPreview(v.title!!,v.videoUrl!!, pos - i, CARD_WIDTH, CARD_HEIGHT)
            }
        }

        for (i in (pos + 1) until rowAdapter.size()) {
            val v = rowAdapter.get(i)
            if (v is Movie) {
                Log.d(TAG.d, "requestPreview: ${v.title}")
                PreviewGenerator.requestPreview(v.title!!,v.videoUrl!!, i - pos, CARD_WIDTH, CARD_HEIGHT)
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
            Log.d(TAG.d, "Selected: " + item)
            if (item is Movie) {
                mBackgroundUri = item.backgroundImageUrl
                startBackgroundTimer()
                // 触发预览调度
                schedulePreviewRequests(item)
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
        mBackgroundTimer?.schedule(object : TimerTask() {
            override fun run() {
                mHandler.post { updateBackground(mBackgroundUri) }
            }
        }, BACKGROUND_UPDATE_DELAY.toLong())
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
