package com.photoclean.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private enum class Screen { GRID, VIEWER, TRASH, SETTINGS }

    private lateinit var settings: AppSettings
    private var screen = Screen.GRID

    // ------------------------------------------------------------ 데이터
    /** 휴지통이 아닌 모든 사진 (최신순) */
    private val allPhotos = mutableListOf<Photo>()
    /** 지금 보기 조건(폴더·모드)에 맞는 사진 */
    private val photos = mutableListOf<Photo>()
    private var rows: List<GridRow> = emptyList()
    private var photoToRow = IntArray(0)
    private var index = 0
    private var viewMode = ViewMode.ALL
    private var bucketFilter: String? = null
    private var folders: List<Folder> = emptyList()

    private var selectionMode = false
    private val selected = LinkedHashSet<Long>()

    /** 되돌리기: 한 번에 휴지통으로 보낸 묶음들 */
    private val undoStack = ArrayDeque<List<Photo>>()
    private var trashedCount = 0
    private var busy = false

    // 휴지통 보내기 요청 (많으면 500장씩 나눠서)
    private val trashQueue = ArrayDeque<List<Photo>>()
    private val trashedNow = ArrayList<Photo>()
    private var pendingTrash: List<Photo>? = null
    private var pendingRestore: List<Photo>? = null
    private var pendingFav: Pair<Photo, Boolean>? = null

    // 휴지통 화면
    private val binItems = mutableListOf<Photo>()
    private val binSelected = LinkedHashSet<Long>()
    private val binQueue = ArrayDeque<List<Photo>>()
    private var binCode = 0
    private var binDone = 0
    private val binAffected = HashSet<Long>()

    // 흐린 사진
    private val blurScores = ConcurrentHashMap<Long, Float>()
    private var blurScanning = false

    // ------------------------------------------------------------ 기반
    private val io = Executors.newFixedThreadPool(2)
    private val thumbIo = Executors.newFixedThreadPool(3)
    private val blurIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val thumbCache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    /** 크게 보기용 (확대해도 선명하도록 화면보다 크게 디코딩) */
    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private var loadToken = 0
    private var loaded = false
    private var askedManage = false
    private var resumeChecked = false
    private var chromeVisible = true

    // ------------------------------------------------------------ 화면 요소
    private lateinit var gridScreen: View
    private lateinit var viewerScreen: View
    private lateinit var trashScreen: View
    private lateinit var settingsScreen: View

    private lateinit var headerNormal: View
    private lateinit var headerSelect: View
    private lateinit var gridTitle: TextView
    private lateinit var gridCount: TextView
    private lateinit var selTitle: TextView
    private lateinit var btnGridUndo: MaterialButton
    private lateinit var btnSelect: MaterialButton
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var chipFolder: Chip
    private lateinit var chipGroupMode: ChipGroup
    private lateinit var gridBanner: MaterialCardView
    private lateinit var blurProgressBox: View
    private lateinit var blurProgressText: TextView
    private lateinit var blurProgress: LinearProgressIndicator
    private lateinit var grid: RecyclerView
    private lateinit var gridLayout: GridLayoutManager
    private lateinit var fastScroller: FastScroller
    private lateinit var gridEmpty: TextView
    private lateinit var gridLoading: CircularProgressIndicator
    private lateinit var btnResume: ExtendedFloatingActionButton
    private lateinit var selBar: View
    private lateinit var btnTrashSelected: MaterialButton
    private val gridAdapter = PhotoGridAdapter()

    private lateinit var photoView: ZoomImageView
    private lateinit var loading: CircularProgressIndicator
    private lateinit var emptyText: TextView
    private lateinit var viewerTop: View
    private lateinit var viewerBottom: View
    private lateinit var counterText: TextView
    private lateinit var infoText: TextView
    private lateinit var zoomPill: TextView
    private lateinit var hintText: TextView
    private lateinit var btnFav: MaterialButton
    private lateinit var btnUndo: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnTrash: MaterialButton
    private lateinit var btnKeep: MaterialButton
    private lateinit var strip: RecyclerView
    private lateinit var stripLayout: LinearLayoutManager
    private val stripAdapter = StripAdapter()

    private lateinit var trashGrid: GridView
    private lateinit var trashTitle: TextView
    private lateinit var trashEmpty: TextView
    private lateinit var trashLoading: CircularProgressIndicator
    private lateinit var btnTrashSelectAll: MaterialButton
    private lateinit var btnRestoreSelected: MaterialButton
    private lateinit var btnDeleteForever: MaterialButton
    private val trashAdapter = TrashAdapter()

    private lateinit var swKeepZoom: MaterialSwitch
    private lateinit var swResume: MaterialSwitch
    private lateinit var tvSwipeSide: TextView
    private lateinit var tvSwipeUp: TextView
    private lateinit var tvSwipeDown: TextView
    private lateinit var tvBlur: TextView
    private lateinit var tvManage: TextView
    private lateinit var tvUpdate: TextView
    private lateinit var swAutoUpdate: MaterialSwitch

    // 업데이트
    private var checkingUpdate = false
    /** '출처를 알 수 없는 앱' 허용하러 갔다 오면 이어서 진행할 릴리즈 */
    private var pendingRelease: Updater.Release? = null

    // ============================================================ 시작

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)

        gridScreen = findViewById(R.id.gridScreen)
        viewerScreen = findViewById(R.id.viewerScreen)
        trashScreen = findViewById(R.id.trashScreen)
        settingsScreen = findViewById(R.id.settingsScreen)

        setupGridScreen()
        setupViewer()
        setupTrashScreen()
        setupSettingsScreen()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        loadBlurCache()
        showScreen(Screen.GRID)
        if (hasReadPermission()) loadAll() else requestReadPermission()
        maybeAutoCheckUpdate()
    }

    override fun onResume() {
        super.onResume()
        updateManageBanner()
        if (screen == Screen.SETTINGS) updateSettingsUi()
        pendingRelease?.let { r ->
            if (packageManager.canRequestPackageInstalls()) {
                pendingRelease = null
                startUpdate(r)
            }
        }
        if (!loaded && hasReadPermission()) loadAll()
        if (hasReadPermission() && !askedManage) {
            askedManage = true
            if (Build.VERSION.SDK_INT >= 31 && !canManageMedia()) showManageMediaDialog(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        thumbIo.shutdownNow()
        blurIo.shutdownNow()
    }

    private fun showScreen(s: Screen) {
        screen = s
        gridScreen.visibility = if (s == Screen.GRID) View.VISIBLE else View.GONE
        viewerScreen.visibility = if (s == Screen.VIEWER) View.VISIBLE else View.GONE
        trashScreen.visibility = if (s == Screen.TRASH) View.VISIBLE else View.GONE
        settingsScreen.visibility = if (s == Screen.SETTINGS) View.VISIBLE else View.GONE
    }

    /** 뒤로가기 처리. false 면 앱 종료. */
    private fun handleBack(): Boolean = when {
        screen == Screen.VIEWER || screen == Screen.SETTINGS -> { showGrid(); true }
        screen == Screen.TRASH -> { closeTrashScreen(); true }
        selectionMode -> { setSelectionMode(false); true }
        else -> false
    }

    // ============================================================ 권한

    private fun readPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasReadPermission(): Boolean =
        readPermissions().any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestReadPermission() = requestPermissions(readPermissions(), REQ_READ)

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_READ) return
        if (hasReadPermission()) {
            loadAll()
        } else {
            showGridMessage("사진 접근 권한이 필요해요\n\n여기를 탭해서 다시 요청하거나\n설정 > 애플리케이션 > 사진 정리 > 권한에서 허용해 주세요")
            gridEmpty.setOnClickListener {
                if (shouldShowRequestPermissionRationale(readPermissions()[0])) requestReadPermission()
                else openAppSettings()
            }
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** '미디어 관리' 권한이 있으면 휴지통·즐겨찾기 변경 시 시스템 확인창이 뜨지 않는다. */
    private fun canManageMedia(): Boolean = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(this)

    private fun updateManageBanner() {
        gridBanner.visibility = if (Build.VERSION.SDK_INT >= 31 && !canManageMedia()) View.VISIBLE else View.GONE
    }

    private fun requestManageMedia() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    private fun showManageMediaDialog(onSkip: (() -> Unit)?) {
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("버튼 한 번에 바로 삭제")
            .setMessage("휴지통 버튼을 누를 때마다 뜨는 확인창을 없애려면 '미디어 관리' 권한을 켜주세요.\n\n다음 화면에서 '사진 정리'의 스위치를 켜고 돌아오면 돼요.")
            .setPositiveButton("권한 켜기") { _, _ -> requestManageMedia() }
        if (onSkip != null) b.setNegativeButton("이번만 그냥 삭제") { _, _ -> onSkip() }
        else b.setNegativeButton("나중에", null)
        b.show()
    }

    // ============================================================ 사진 불러오기

    private fun loadAll() {
        loaded = true
        gridEmpty.setOnClickListener(null)
        if (allPhotos.isEmpty()) gridLoading.visibility = View.VISIBLE
        io.execute {
            val list = MediaRepo.query(contentResolver)
            main.post {
                val keep = photos.getOrNull(index)?.id
                allPhotos.clear()
                allPhotos.addAll(list)
                folders = PhotoLogic.groupFolders(allPhotos)
                if (bucketFilter != null && folders.none { it.bucketId == bucketFilter }) bucketFilter = null
                gridLoading.visibility = View.GONE
                applyFilters(keep)
                refresh()
                checkResume()
                if (viewMode == ViewMode.BLURRY) ensureBlurScan()
            }
        }
    }

    /** 보기 조건에 맞게 [photos] 다시 만들기. keepId 사진이 있으면 그 위치 유지. */
    private fun applyFilters(keepId: Long?) {
        val list = PhotoLogic.applyFilter(
            allPhotos, viewMode, bucketFilter, blurScores,
            PhotoLogic.blurThreshold(settings.blurSensitivity),
        )
        photos.clear()
        photos.addAll(list)
        val keepPos = keepId?.let { id -> photos.indexOfFirst { it.id == id } } ?: -1
        index = if (keepPos >= 0) keepPos else index.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
        val ids = photos.mapTo(HashSet()) { it.id }
        selected.retainAll(ids)
        rebuildRows()
    }

    private fun rebuildRows() {
        rows = PhotoLogic.buildRows(photos, withHeaders = viewMode != ViewMode.BLURRY)
        photoToRow = IntArray(photos.size)
        rows.forEachIndexed { r, row -> if (row is GridRow.Item) photoToRow[row.photoIndex] = r }
        gridAdapter.notifyDataSetChanged()
    }

    /** 지금 보이는 화면 새로 그리기 */
    private fun refresh() {
        when (screen) {
            Screen.VIEWER -> show()
            Screen.GRID -> updateGridHeader()
            else -> Unit
        }
    }

    // ============================================================ 전체 보기

    private fun setupGridScreen() {
        headerNormal = findViewById(R.id.headerNormal)
        headerSelect = findViewById(R.id.headerSelect)
        gridTitle = findViewById(R.id.gridTitle)
        gridCount = findViewById(R.id.gridCount)
        selTitle = findViewById(R.id.selTitle)
        btnGridUndo = findViewById(R.id.btnGridUndo)
        btnSelect = findViewById(R.id.btnSelect)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        chipFolder = findViewById(R.id.chipFolder)
        chipGroupMode = findViewById(R.id.chipGroupMode)
        gridBanner = findViewById(R.id.gridBanner)
        blurProgressBox = findViewById(R.id.blurProgressBox)
        blurProgressText = findViewById(R.id.blurProgressText)
        blurProgress = findViewById(R.id.blurProgress)
        grid = findViewById(R.id.grid)
        fastScroller = findViewById(R.id.fastScroller)
        gridEmpty = findViewById(R.id.gridEmpty)
        gridLoading = findViewById(R.id.gridLoading)
        btnResume = findViewById(R.id.btnResume)
        selBar = findViewById(R.id.selBar)
        btnTrashSelected = findViewById(R.id.btnTrashSelected)

        gridLayout = GridLayoutManager(this, settings.columns)
        gridLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (rows.getOrNull(position) is GridRow.Header) gridLayout.spanCount else 1
        }
        grid.layoutManager = gridLayout
        grid.adapter = gridAdapter
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        fastScroller.attach(grid)
        fastScroller.labelFor = { pos -> labelForRow(pos) }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) btnResume.shrink() else if (dy < -8) btnResume.extend()
            }
        })
        setupGridPinch()

        btnSelect.setOnClickListener { setSelectionMode(true) }
        findViewById<View>(R.id.btnSelCancel).setOnClickListener { setSelectionMode(false) }
        btnSelectAll.setOnClickListener {
            if (selected.size == photos.size) selected.clear() else photos.forEach { selected.add(it.id) }
            gridAdapter.notifyDataSetChanged()
            updateGridHeader()
        }
        btnTrashSelected.setOnClickListener { trashSelected() }
        btnGridUndo.setOnClickListener { undo() }
        findViewById<View>(R.id.btnOpenTrash).setOnClickListener { openTrashScreen() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { openSettings() }
        findViewById<View>(R.id.bannerOn).setOnClickListener { requestManageMedia() }
        chipFolder.setOnClickListener { showFolderPicker() }
        chipFolder.setOnCloseIconClickListener { showFolderPicker() }
        chipGroupMode.setOnCheckedStateChangeListener { _, ids ->
            setViewMode(
                when (ids.firstOrNull()) {
                    R.id.chipFav -> ViewMode.FAVORITES
                    R.id.chipBlur -> ViewMode.BLURRY
                    else -> ViewMode.ALL
                }
            )
        }
        btnResume.setOnClickListener {
            val pos = photos.indexOfFirst { it.id == settings.lastPhotoId }
            btnResume.visibility = View.GONE
            if (pos >= 0) openViewer(pos)
        }
    }

    private fun labelForRow(pos: Int): String {
        if (viewMode == ViewMode.BLURRY) return ""
        return when (val row = rows.getOrNull(pos)) {
            is GridRow.Header -> row.label
            is GridRow.Item -> PhotoLogic.monthLabelOf(photos[row.photoIndex].dateMillis)
            null -> ""
        }
    }

    private fun showGridMessage(msg: String) {
        gridEmpty.text = msg
        gridEmpty.visibility = View.VISIBLE
    }

    private fun showGrid() {
        showScreen(Screen.GRID)
        gridAdapter.notifyDataSetChanged()
        updateGridHeader()
        if (photos.isNotEmpty()) {
            val r = photoToRow.getOrElse(index.coerceIn(0, photos.size - 1)) { 0 }
            val first = gridLayout.findFirstVisibleItemPosition()
            val last = gridLayout.findLastVisibleItemPosition()
            if (r < first || r > last) gridLayout.scrollToPositionWithOffset(r, grid.height / 3)
        }
    }

    private fun updateGridHeader() {
        headerNormal.visibility = if (selectionMode) View.GONE else View.VISIBLE
        headerSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
        selBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        btnGridUndo.visibility = if (undoStack.isNotEmpty()) View.VISIBLE else View.GONE
        btnGridUndo.isEnabled = !busy

        gridTitle.text = when (viewMode) {
            ViewMode.ALL -> "사진 정리"
            ViewMode.FAVORITES -> "즐겨찾기"
            ViewMode.BLURRY -> "흐린 사진"
        }
        val folder = folders.firstOrNull { it.bucketId == bucketFilter }
        gridCount.text = buildString {
            if (folder != null) append(folder.name).append(" · ")
            append("%,d장".format(photos.size))
            if (trashedCount > 0) append(" · 오늘 %,d장 정리".format(trashedCount))
        }
        chipFolder.text = folder?.name ?: "모든 폴더"

        selTitle.text = if (selected.isEmpty()) "사진 선택" else "%,d장 선택".format(selected.size)
        btnSelectAll.text = if (photos.isNotEmpty() && selected.size == photos.size) "선택 해제" else "전체 선택"
        btnTrashSelected.text = if (selected.isEmpty()) "사진을 골라주세요" else "%,d장 휴지통으로".format(selected.size)
        btnTrashSelected.isEnabled = selected.isNotEmpty() && !busy

        if (photos.isEmpty() && loaded && gridLoading.visibility != View.VISIBLE) {
            showGridMessage(
                when (viewMode) {
                    ViewMode.FAVORITES -> "즐겨찾기한 사진이 없어요\n\n사진을 크게 연 뒤 위쪽 ♡ 를 누르면\n즐겨찾기에 추가돼요"
                    ViewMode.BLURRY -> if (blurScanning) "흐린 사진을 찾고 있어요…" else "흐린 사진이 없어요 👍\n\n설정에서 민감도를 높이면 더 찾아봐요"
                    ViewMode.ALL -> "정리할 사진이 없어요 🎉"
                }
            )
        } else if (photos.isNotEmpty()) {
            gridEmpty.visibility = View.GONE
        }
    }

    private fun setSelectionMode(on: Boolean) {
        selectionMode = on
        if (!on) selected.clear()
        if (on) btnResume.visibility = View.GONE
        gridAdapter.notifyDataSetChanged()
        updateGridHeader()
    }

    private fun toggleSelect(photoIndex: Int) {
        val p = photos.getOrNull(photoIndex) ?: return
        if (!selected.remove(p.id)) selected.add(p.id)
        gridAdapter.notifyItemChanged(photoToRow[photoIndex])
        updateGridHeader()
    }

    private fun setViewMode(m: ViewMode) {
        if (m == viewMode) return
        if (selectionMode) setSelectionMode(false)
        viewMode = m
        btnResume.visibility = View.GONE
        applyFilters(null)
        index = 0
        grid.scrollToPosition(0)
        blurProgressBox.visibility = View.GONE
        if (m == ViewMode.BLURRY) ensureBlurScan()
        updateGridHeader()
    }

    private fun setFolder(bucketId: String?) {
        if (bucketId == bucketFilter) return
        if (selectionMode) setSelectionMode(false)
        bucketFilter = bucketId
        btnResume.visibility = View.GONE
        applyFilters(null)
        index = 0
        grid.scrollToPosition(0)
        updateGridHeader()
    }

    /** 폴더 고르기 시트 */
    private fun showFolderPicker() {
        val sheet = BottomSheetDialog(this)
        val dp = resources.displayMetrics.density
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (24 * dp).toInt())
        }
        list.addView(TextView(this).apply {
            text = "폴더"
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.text))
            paint.isFakeBoldText = true
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
        })
        val rippleRes = android.util.TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        fun addRow(bucketId: String?, name: String, count: Int, cover: Photo?) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
                isClickable = true
                isFocusable = true
                setBackgroundResource(rippleRes)
                setOnClickListener { sheet.dismiss(); setFolder(bucketId) }
            }
            val thumb = ShapeableImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ContextCompat.getColor(context, R.color.surface3))
                shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(12 * dp).build()
                if (cover == null) {
                    setImageResource(R.drawable.ic_folder)
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
                    scaleType = ImageView.ScaleType.CENTER
                } else {
                    loadThumb(this, cover)
                }
            }
            row.addView(thumb, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text))
            })
            texts.addView(TextView(this).apply {
                text = "%,d장".format(count)
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text2))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (16 * dp).toInt()
            })
            if (bucketId == bucketFilter) {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
                }, LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))
            }
            list.addView(row)
        }
        addRow(null, "모든 폴더", allPhotos.size, null)
        folders.forEach { addRow(it.bucketId, it.name, it.count, it.cover) }
        sheet.setContentView(NestedScrollView(this).apply { addView(list) })
        sheet.show()
    }

    /** 전체 보기에서 두 손가락으로 벌리면 크게(칸 수 ↓), 오므리면 작게(칸 수 ↑). */
    private fun setupGridPinch() {
        var acc = 1f
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                val next = when {
                    acc > 1.25f -> gridLayout.spanCount - 1
                    acc < 0.8f -> gridLayout.spanCount + 1
                    else -> return true
                }.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                acc = 1f
                if (next != gridLayout.spanCount) {
                    val first = gridLayout.findFirstVisibleItemPosition()
                    gridLayout.spanCount = next
                    settings.columns = next
                    if (first >= 0) gridLayout.scrollToPositionWithOffset(first, 0)
                }
                return true
            }
        })
        grid.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                // 두 손가락이 닿으면 목록 스크롤/탭 대신 크기 조절
                return e.pointerCount > 1
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                detector.onTouchEvent(e)
            }
        })
    }

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.headerTitle)
        val count: TextView = v.findViewById(R.id.headerCount)
    }

    private class CellHolder(val cell: GridCell) : RecyclerView.ViewHolder(cell)

    private inner class PhotoGridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is GridRow.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                return HeaderHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_month_header, parent, false))
            }
            val cell = GridCell(parent.context)
            val gap = (2 * parent.resources.displayMetrics.density).toInt()
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(gap, gap, gap, gap) }
            val h = CellHolder(cell)
            cell.setOnClickListener {
                val pi = photoIndexAt(h.bindingAdapterPosition) ?: return@setOnClickListener
                if (selectionMode) toggleSelect(pi) else openViewer(pi)
            }
            cell.setOnLongClickListener {
                val pi = photoIndexAt(h.bindingAdapterPosition) ?: return@setOnLongClickListener false
                if (!selectionMode) setSelectionMode(true)
                toggleSelect(pi)
                true
            }
            return h
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is GridRow.Header -> (holder as HeaderHolder).apply {
                    title.text = row.label
                    count.text = "%,d장".format(row.count)
                }
                is GridRow.Item -> (holder as CellHolder).cell.apply {
                    val p = photos[row.photoIndex]
                    setSelectionState(selectionMode, p.id in selected)
                    setFavorite(p.favorite)
                    setLabel(null)
                    loadThumb(image, p)
                }
            }
        }
    }

    private fun photoIndexAt(adapterPos: Int): Int? =
        (rows.getOrNull(adapterPos) as? GridRow.Item)?.photoIndex

    /** 썸네일을 비동기로 불러와 iv 에 넣는다 (스크롤로 재사용된 칸이면 버림). */
    private fun loadThumb(iv: ImageView, p: Photo) {
        iv.tag = p.id
        val cached = thumbCache.get(p.id)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageDrawable(null)
        thumbIo.execute {
            if (iv.tag != p.id) return@execute
            val bmp = try {
                contentResolver.loadThumbnail(p.uri, Size(THUMB, THUMB), null)
            } catch (e: Exception) {
                null
            } ?: return@execute
            thumbCache.put(p.id, bmp)
            main.post { if (iv.tag == p.id) iv.setImageBitmap(bmp) }
        }
    }

    // ============================================================ 이어서 보기

    /** 처음 불러온 뒤 한 번: 마지막으로 보던 사진 위치로 이동하고 '이어서 보기' 버튼 표시 */
    private fun checkResume() {
        if (resumeChecked) return
        resumeChecked = true
        if (!settings.resume) return
        val pos = photos.indexOfFirst { it.id == settings.lastPhotoId }
        if (pos < 0) return
        index = pos
        grid.post { gridLayout.scrollToPositionWithOffset(photoToRow[pos], grid.height / 3) }
        btnResume.visibility = View.VISIBLE
        btnResume.extend()
    }

    // ============================================================ 한 장씩 보기

    private fun setupViewer() {
        photoView = findViewById(R.id.photo)
        loading = findViewById(R.id.loading)
        emptyText = findViewById(R.id.empty)
        viewerTop = findViewById(R.id.viewerTop)
        viewerBottom = findViewById(R.id.viewerBottom)
        counterText = findViewById(R.id.counter)
        infoText = findViewById(R.id.info)
        zoomPill = findViewById(R.id.zoomPill)
        hintText = findViewById(R.id.hint)
        btnFav = findViewById(R.id.btnFav)
        btnUndo = findViewById(R.id.btnUndo)
        btnPrev = findViewById(R.id.btnPrev)
        btnTrash = findViewById(R.id.btnTrash)
        btnKeep = findViewById(R.id.btnKeep)
        strip = findViewById(R.id.strip)

        findViewById<View>(R.id.btnBack).setOnClickListener { showGrid() }
        btnTrash.setOnClickListener { trashCurrent() }
        btnKeep.setOnClickListener { move(+1) }
        btnPrev.setOnClickListener { move(-1) }
        btnUndo.setOnClickListener { undo() }
        btnFav.setOnClickListener { toggleFavorite() }

        stripLayout = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        strip.layoutManager = stripLayout
        strip.adapter = stripAdapter
        strip.itemAnimator = null

        photoView.keepZoomAcrossPhotos = settings.keepZoom
        photoView.onZoomChanged = { zoomed ->
            zoomPill.visibility = if (zoomed && settings.keepZoom) View.VISIBLE else View.GONE
        }
        photoView.onSingleTap = { setChromeVisible(!chromeVisible) }
        photoView.onSwipe = { dx, dy ->
            val min = 60 * resources.displayMetrics.density
            when {
                abs(dx) > abs(dy) && abs(dx) > min -> {
                    val next = (dx < 0) != settings.swipeReverse
                    move(if (next) +1 else -1)
                }
                dy < -min && abs(dy) > abs(dx) -> doSwipeAction(settings.swipeUp)
                dy > min && abs(dy) > abs(dx) -> doSwipeAction(settings.swipeDown)
            }
        }
        updateHint()
    }

    private fun doSwipeAction(a: SwipeAction) {
        when (a) {
            SwipeAction.TRASH -> trashCurrent()
            SwipeAction.FAVORITE -> toggleFavorite()
            SwipeAction.GRID -> showGrid()
            SwipeAction.NONE -> Unit
        }
    }

    private fun updateHint() {
        val left = if (settings.swipeReverse) "이전" else "다음"
        val right = if (settings.swipeReverse) "다음" else "이전"
        val parts = mutableListOf("← $left", "→ $right")
        if (settings.swipeUp != SwipeAction.NONE) parts += "↑ ${settings.swipeUp.short}"
        if (settings.swipeDown != SwipeAction.NONE) parts += "↓ ${settings.swipeDown.short}"
        parts += "탭: 사진만 보기"
        hintText.text = parts.joinToString("   ")
    }

    /** 위/아래 막대 보이기/숨기기 (사진만 크게 보기) */
    private fun setChromeVisible(v: Boolean) {
        chromeVisible = v
        listOf(viewerTop, viewerBottom).forEach { bar ->
            bar.animate().cancel()
            if (v) {
                bar.visibility = View.VISIBLE
                bar.animate().alpha(1f).setDuration(180).start()
            } else {
                bar.animate().alpha(0f).setDuration(180).withEndAction { bar.visibility = View.GONE }.start()
            }
        }
    }

    private fun openViewer(pos: Int) {
        if (pos !in photos.indices) return
        index = pos
        btnResume.visibility = View.GONE
        showScreen(Screen.VIEWER)
        setChromeVisible(true)
        show()
    }

    private fun show() {
        btnUndo.isEnabled = undoStack.isNotEmpty() && !busy
        btnUndo.alpha = if (btnUndo.isEnabled) 1f else 0.35f
        stripAdapter.notifyDataSetChanged()

        if (photos.isEmpty()) {
            photoView.setImageDrawable(null)
            loading.visibility = View.GONE
            emptyText.text = if (trashedCount > 0) "다 봤어요 🎉\n오늘 %,d장을 정리했어요".format(trashedCount) else "사진이 없어요"
            emptyText.visibility = View.VISIBLE
            counterText.text = ""
            infoText.text = ""
            listOf(btnTrash, btnKeep, btnPrev, btnFav).forEach { it.isEnabled = false }
            return
        }
        index = index.coerceIn(0, photos.size - 1)
        emptyText.visibility = View.GONE
        listOf(btnTrash, btnKeep, btnFav).forEach { it.isEnabled = !busy }
        btnPrev.isEnabled = index > 0 && !busy

        val p = photos[index]
        counterText.text = "%,d / %,d".format(index + 1, photos.size)
        infoText.text = listOf(
            DATE_FMT.format(Date(p.dateMillis)),
            Formatter.formatShortFileSize(this, p.size),
            PhotoLogic.folderDisplayName(p.bucketName),
        ).joinToString("  ·  ")
        btnFav.setIconResource(if (p.favorite) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        btnFav.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (p.favorite) R.color.heart else R.color.wood_dark)
        )

        displayImage(p)
        centerStrip()
        photos.getOrNull(index + 1)?.let { prefetch(it) }
        settings.lastPhotoId = p.id
    }

    private fun displayImage(p: Photo) {
        val token = ++loadToken
        val cached = cache.get(p.id)
        if (cached != null) {
            loading.visibility = View.GONE
            photoView.setImageBitmap(cached)
            return
        }
        photoView.setImageDrawable(null)
        loading.visibility = View.VISIBLE
        io.execute {
            val bmp = decode(p)
            main.post {
                if (token != loadToken) return@post
                loading.visibility = View.GONE
                if (bmp != null) photoView.setImageBitmap(bmp)
                else infoText.text = "${infoText.text}  ·  미리보기 불가"
            }
        }
    }

    private fun prefetch(p: Photo) {
        if (cache.get(p.id) != null) return
        io.execute { decode(p) }
    }

    /** 확대해도 선명하게: 화면의 2배(최대 4096px)까지 디코딩 (EXIF 회전 자동 적용) */
    private fun decode(p: Photo): Bitmap? {
        cache.get(p.id)?.let { return it }
        return try {
            val maxSide = resources.displayMetrics.let { minOf(maxOf(it.widthPixels, it.heightPixels) * 2, 4096) }
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, p.uri)) { d, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
            cache.put(p.id, bmp)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun move(delta: Int) {
        if (photos.isEmpty() || busy) return
        val next = index + delta
        if (next < 0) return
        if (next >= photos.size) {
            Toast.makeText(this, "마지막 사진이에요", Toast.LENGTH_SHORT).show()
            return
        }
        index = next
        show()
    }

    // 썸네일 줄
    private class StripHolder(val image: ShapeableImageView) : RecyclerView.ViewHolder(image)

    private inner class StripAdapter : RecyclerView.Adapter<StripHolder>() {
        private val dp by lazy { resources.displayMetrics.density }
        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StripHolder {
            val size = (52 * dp).toInt()
            val iv = ShapeableImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(size, size).apply {
                    val m = (3 * dp).toInt()
                    setMargins(m, (4 * dp).toInt(), m, (4 * dp).toInt())
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                val shape = ShapeAppearanceModel.builder().setAllCornerSizes(10 * dp).build()
                shapeAppearanceModel = shape
                // 배경도 같은 둥근 모양으로 (네모 모서리가 비치지 않게)
                background = com.google.android.material.shape.MaterialShapeDrawable(shape).apply {
                    fillColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surface2))
                }
                strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
                val pad = (1.5f * dp).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val h = StripHolder(iv)
            iv.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && !busy) {
                    index = pos
                    show()
                }
            }
            return h
        }

        override fun onBindViewHolder(h: StripHolder, position: Int) {
            val current = position == index
            h.image.strokeWidth = if (current) 2.5f * dp else 0f
            h.image.alpha = if (current) 1f else 0.55f
            loadThumb(h.image, photos[position])
        }
    }

    /** 지금 사진이 썸네일 줄 가운데 오도록 */
    private fun centerStrip() {
        if (photos.isEmpty()) return
        strip.post {
            val item = (58 * resources.displayMetrics.density).toInt()
            stripLayout.scrollToPositionWithOffset(index, (strip.width - item) / 2)
        }
    }

    // ============================================================ 휴지통으로 보내기

    private fun trashCurrent() {
        if (photos.isEmpty() || busy) return
        requestTrash(listOf(photos[index]))
    }

    private fun trashSelected() {
        if (busy || selected.isEmpty()) return
        val list = photos.filter { it.id in selected }
        if (list.isNotEmpty()) requestTrash(list)
    }

    /** 미디어 관리 권한이 없으면 먼저 안내한 뒤 진행 */
    private fun requestTrash(list: List<Photo>) {
        if (Build.VERSION.SDK_INT >= 31 && !canManageMedia()) {
            showManageMediaDialog { startTrash(list) }
            return
        }
        startTrash(list)
    }

    private fun startTrash(list: List<Photo>) {
        if (busy) return
        trashQueue.clear()
        trashedNow.clear()
        list.chunked(TRASH_CHUNK).forEach { trashQueue.addLast(it) }
        busy = true
        nextTrashChunk()
    }

    private fun nextTrashChunk() {
        val chunk = trashQueue.removeFirstOrNull()
        if (chunk == null) {
            finishTrash()
            return
        }
        pendingTrash = chunk
        try {
            // '미디어 관리' 권한이 있으면 확인창 없이 바로 RESULT_OK 로 돌아온다
            val pi = MediaStore.createTrashRequest(contentResolver, chunk.map { it.uri }, true)
            @Suppress("DEPRECATION")
            startIntentSenderForResult(pi.intentSender, REQ_TRASH, null, 0, 0, 0)
        } catch (e: Exception) {
            pendingTrash = null
            trashQueue.clear()
            Toast.makeText(this, "휴지통으로 보내지 못했어요: ${e.message}", Toast.LENGTH_SHORT).show()
            finishTrash()
        }
    }

    private fun finishTrash() {
        busy = false
        if (trashedNow.isNotEmpty()) {
            val ids = trashedNow.mapTo(HashSet()) { it.id }
            allPhotos.removeAll { it.id in ids }
            photos.removeAll { it.id in ids } // index 는 그대로 → 지운 자리에 다음 사진이 온다
            selected.removeAll(ids)
            ids.forEach { thumbCache.remove(it); cache.remove(it) }
            undoStack.addLast(trashedNow.toList())
            if (undoStack.size > 50) undoStack.removeFirst()
            trashedCount += trashedNow.size
            if (trashedNow.size > 1) {
                Toast.makeText(this, "%,d장을 휴지통으로 보냈어요".format(trashedNow.size), Toast.LENGTH_SHORT).show()
            }
            trashedNow.clear()
            folders = PhotoLogic.groupFolders(allPhotos)
            rebuildRows()
        }
        if (selectionMode) setSelectionMode(false)
        refresh()
    }

    private fun undo() {
        if (busy) return
        val last = undoStack.lastOrNull() ?: return
        pendingRestore = last
        busy = true
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, last.map { it.uri }, false)
            @Suppress("DEPRECATION")
            startIntentSenderForResult(pi.intentSender, REQ_RESTORE, null, 0, 0, 0)
        } catch (e: Exception) {
            busy = false
            pendingRestore = null
            Toast.makeText(this, "되돌리지 못했어요: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onRestoreResult(ok: Boolean) {
        busy = false
        val batch = pendingRestore ?: return
        pendingRestore = null
        if (ok) {
            undoStack.removeLastOrNull()
            val have = allPhotos.mapTo(HashSet()) { it.id }
            batch.filter { it.id !in have }.forEach { PhotoLogic.insertSorted(allPhotos, it) }
            trashedCount = (trashedCount - batch.size).coerceAtLeast(0)
            folders = PhotoLogic.groupFolders(allPhotos)
            applyFilters(batch.first().id)
            Toast.makeText(this, "%,d장 복원했어요".format(batch.size), Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    // ============================================================ 즐겨찾기

    private fun toggleFavorite() {
        if (photos.isEmpty() || busy) return
        val p = photos[index]
        val value = !p.favorite
        pendingFav = p to value
        busy = true
        try {
            val pi = MediaStore.createFavoriteRequest(contentResolver, listOf(p.uri), value)
            @Suppress("DEPRECATION")
            startIntentSenderForResult(pi.intentSender, REQ_FAVORITE, null, 0, 0, 0)
        } catch (e: Exception) {
            busy = false
            pendingFav = null
            Toast.makeText(this, "즐겨찾기를 바꾸지 못했어요: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onFavoriteResult(ok: Boolean) {
        busy = false
        val (p, value) = pendingFav ?: return
        pendingFav = null
        if (ok) {
            // 즐겨찾기 보기에서 하트를 꺼도 바로 사라지지 않게 지금 목록에는 남겨둔다
            val a = allPhotos.indexOfFirst { it.id == p.id }
            if (a >= 0) allPhotos[a] = allPhotos[a].copy(favorite = value)
            val i = photos.indexOfFirst { it.id == p.id }
            if (i >= 0) photos[i] = photos[i].copy(favorite = value)
            Toast.makeText(this, if (value) "즐겨찾기에 추가했어요" else "즐겨찾기에서 뺐어요", Toast.LENGTH_SHORT).show()
            gridAdapter.notifyDataSetChanged()
        }
        refresh()
    }

    // ============================================================ 흐린 사진 찾기

    private fun blurFile() = File(filesDir, "blur_scores.txt")

    /** 이전에 계산해 둔 선명도 점수 불러오기 (다시 계산하지 않도록) */
    private fun loadBlurCache() {
        blurIo.execute {
            try {
                blurFile().takeIf { it.exists() }?.forEachLine { line ->
                    val sp = line.indexOf(' ')
                    if (sp > 0) {
                        val id = line.substring(0, sp).toLongOrNull()
                        val s = line.substring(sp + 1).toFloatOrNull()
                        if (id != null && s != null) blurScores[id] = s
                    }
                }
            } catch (e: Exception) {
                // 캐시가 깨졌으면 그냥 다시 계산
            }
        }
    }

    /** 아직 점수가 없는 사진들의 선명도를 폰 안에서 계산 (결과는 파일에 저장) */
    private fun ensureBlurScan() {
        if (blurScanning) return
        val snapshot = allPhotos.toList()
        blurScanning = true
        blurIo.execute {
            val todo = snapshot.filter { !blurScores.containsKey(it.id) }
            val total = todo.size
            val out = StringBuilder()
            var done = 0
            for (p in todo) {
                if (Thread.currentThread().isInterrupted) break
                val s = scoreBlur(p)
                blurScores[p.id] = s
                out.append(p.id).append(' ').append(s).append('\n')
                done++
                if (done % 25 == 0 || done == total) {
                    try {
                        blurFile().appendText(out.toString())
                    } catch (e: Exception) {
                        // 저장 실패 시 다음에 다시 계산
                    }
                    out.setLength(0)
                    val d = done
                    main.post { onBlurProgress(d, total, finished = false) }
                }
            }
            main.post { onBlurProgress(total, total, finished = true) }
        }
    }

    private fun onBlurProgress(done: Int, total: Int, finished: Boolean) {
        if (finished) blurScanning = false
        if (viewMode != ViewMode.BLURRY) return
        if (finished || total == 0) {
            blurProgressBox.visibility = View.GONE
        } else {
            blurProgressBox.visibility = View.VISIBLE
            blurProgressText.text = "흐린 사진 찾는 중  %,d / %,d".format(done, total)
            blurProgress.max = total
            blurProgress.setProgressCompat(done, true)
        }
        // 찾는 동안에도 조금씩 결과를 보여준다
        if (finished || done % 200 == 0) {
            applyFilters(photos.getOrNull(index)?.id)
            refresh()
        }
    }

    /** 선명도 점수 = 256px 썸네일 밝기의 라플라시안 분산 (낮을수록 흐림) */
    private fun scoreBlur(p: Photo): Float = try {
        var b = contentResolver.loadThumbnail(p.uri, Size(BLUR_SIZE, BLUR_SIZE), null)
        if (b.config == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false)
        val scale = BLUR_SIZE.toFloat() / maxOf(b.width, b.height)
        if (scale < 1f) {
            b = Bitmap.createScaledBitmap(
                b,
                (b.width * scale).toInt().coerceAtLeast(3),
                (b.height * scale).toInt().coerceAtLeast(3),
                true,
            )
        }
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(px.size) { i ->
            val c = px[i]
            (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        PhotoLogic.laplacianVariance(gray, w, h).toFloat()
    } catch (e: Exception) {
        Float.MAX_VALUE // 읽지 못한 사진은 흐린 사진으로 치지 않음
    }

    // ============================================================ 휴지통 화면

    private fun setupTrashScreen() {
        trashGrid = findViewById(R.id.trashGrid)
        trashTitle = findViewById(R.id.trashTitle)
        trashEmpty = findViewById(R.id.trashEmpty)
        trashLoading = findViewById(R.id.trashLoading)
        btnTrashSelectAll = findViewById(R.id.btnTrashSelectAll)
        btnRestoreSelected = findViewById(R.id.btnRestoreSelected)
        btnDeleteForever = findViewById(R.id.btnDeleteForever)

        trashGrid.adapter = trashAdapter
        trashGrid.setOnItemClickListener { _, _, pos, _ ->
            val id = binItems.getOrNull(pos)?.id ?: return@setOnItemClickListener
            if (!binSelected.remove(id)) binSelected.add(id)
            updateTrashHeader()
        }
        findViewById<View>(R.id.btnTrashBack).setOnClickListener { closeTrashScreen() }
        btnTrashSelectAll.setOnClickListener {
            if (binSelected.size == binItems.size) binSelected.clear() else binItems.forEach { binSelected.add(it.id) }
            updateTrashHeader()
        }
        btnRestoreSelected.setOnClickListener {
            val list = binItems.filter { it.id in binSelected }
            if (list.isNotEmpty() && !busy) startBinRequests(REQ_BIN_RESTORE, list)
        }
        btnDeleteForever.setOnClickListener { confirmDeleteForever() }
    }

    private fun openTrashScreen() {
        if (selectionMode) setSelectionMode(false)
        showScreen(Screen.TRASH)
        trashGrid.numColumns = gridLayout.spanCount
        loadTrash()
    }

    private fun closeTrashScreen() {
        binSelected.clear()
        showGrid()
    }

    private fun loadTrash() {
        trashLoading.visibility = View.VISIBLE
        trashEmpty.visibility = View.GONE
        io.execute {
            val list = if (hasReadPermission()) MediaRepo.query(contentResolver, trashedOnly = true) else emptyList()
            main.post {
                binItems.clear()
                binItems.addAll(list)
                binSelected.retainAll(list.mapTo(HashSet()) { it.id })
                trashLoading.visibility = View.GONE
                updateTrashHeader()
            }
        }
    }

    private fun updateTrashHeader() {
        trashAdapter.notifyDataSetChanged()
        trashEmpty.visibility = if (binItems.isEmpty() && trashLoading.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        trashTitle.text = if (binSelected.isNotEmpty()) "%,d장 선택".format(binSelected.size) else "휴지통 %,d장".format(binItems.size)
        btnTrashSelectAll.text = if (binItems.isNotEmpty() && binSelected.size == binItems.size) "선택 해제" else "전체 선택"
        btnTrashSelectAll.isEnabled = binItems.isNotEmpty()
        val on = binSelected.isNotEmpty() && !busy
        btnRestoreSelected.isEnabled = on
        btnDeleteForever.isEnabled = on
        btnRestoreSelected.text = if (binSelected.isEmpty()) "복원" else "%,d장 복원".format(binSelected.size)
        btnDeleteForever.text = if (binSelected.isEmpty()) "영구 삭제" else "%,d장 영구 삭제".format(binSelected.size)
    }

    private fun daysLeftLabel(p: Photo): String? {
        if (p.expiresMillis <= 0) return null
        val left = p.expiresMillis - System.currentTimeMillis()
        if (left <= 0) return "곧 삭제"
        val days = Math.ceil(left / 86_400_000.0).toInt()
        return "${days.coerceAtLeast(1)}일 남음"
    }

    private fun confirmDeleteForever() {
        val list = binItems.filter { it.id in binSelected }
        if (list.isEmpty() || busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle("%,d장을 영구 삭제할까요?".format(list.size))
            .setMessage("영구 삭제한 사진은 되돌릴 수 없어요.")
            .setPositiveButton("영구 삭제") { _, _ -> startBinRequests(REQ_BIN_DELETE, list) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startBinRequests(code: Int, list: List<Photo>) {
        binQueue.clear()
        binAffected.clear()
        list.chunked(TRASH_CHUNK).forEach { binQueue.addLast(it) }
        binCode = code
        binDone = 0
        busy = true
        updateTrashHeader()
        nextBinRequest()
    }

    private fun nextBinRequest() {
        val chunk = binQueue.firstOrNull()
        if (chunk == null) {
            finishBinRequests()
            return
        }
        try {
            val uris = chunk.map { it.uri }
            val pi = if (binCode == REQ_BIN_RESTORE) MediaStore.createTrashRequest(contentResolver, uris, false)
            else MediaStore.createDeleteRequest(contentResolver, uris)
            @Suppress("DEPRECATION")
            startIntentSenderForResult(pi.intentSender, binCode, null, 0, 0, 0)
        } catch (e: Exception) {
            binQueue.clear()
            Toast.makeText(this, "처리하지 못했어요: ${e.message}", Toast.LENGTH_SHORT).show()
            finishBinRequests()
        }
    }

    private fun onBinResult(ok: Boolean) {
        val chunk = binQueue.removeFirstOrNull()
        if (ok && chunk != null) {
            binDone += chunk.size
            chunk.forEach { binAffected.add(it.id) }
            nextBinRequest()
        } else {
            binQueue.clear()
            finishBinRequests()
        }
    }

    private fun finishBinRequests() {
        busy = false
        if (binDone > 0) {
            Toast.makeText(
                this,
                if (binCode == REQ_BIN_RESTORE) "%,d장 복원했어요".format(binDone) else "%,d장 영구 삭제했어요".format(binDone),
                Toast.LENGTH_SHORT,
            ).show()
            // 처리된 사진은 '되돌리기' 기록에서 뺀다 (이미 복원됐거나 없어진 사진)
            val pruned = undoStack.map { b -> b.filter { it.id !in binAffected } }.filter { it.isNotEmpty() }
            undoStack.clear()
            undoStack.addAll(pruned)
            binSelected.clear()
            if (binCode == REQ_BIN_RESTORE) loadAll()
        }
        binDone = 0
        loadTrash()
    }

    private inner class TrashAdapter : BaseAdapter() {
        override fun getCount() = binItems.size
        override fun getItem(position: Int) = binItems[position]
        override fun getItemId(position: Int) = binItems[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? GridCell) ?: GridCell(this@MainActivity)
            val p = binItems[position]
            cell.setSelectionState(true, p.id in binSelected)
            cell.setLabel(daysLeftLabel(p))
            cell.setFavorite(false)
            loadThumb(cell.image, p)
            return cell
        }
    }

    // ============================================================ 설정 화면

    private fun setupSettingsScreen() {
        swKeepZoom = findViewById(R.id.swKeepZoom)
        swResume = findViewById(R.id.swResume)
        tvSwipeSide = findViewById(R.id.tvSwipeSide)
        tvSwipeUp = findViewById(R.id.tvSwipeUp)
        tvSwipeDown = findViewById(R.id.tvSwipeDown)
        tvBlur = findViewById(R.id.tvBlur)
        tvManage = findViewById(R.id.tvManage)

        findViewById<View>(R.id.btnSettingsBack).setOnClickListener { showGrid() }
        findViewById<View>(R.id.rowKeepZoom).setOnClickListener { swKeepZoom.toggle() }
        findViewById<View>(R.id.rowResume).setOnClickListener { swResume.toggle() }
        swKeepZoom.setOnCheckedChangeListener { _, on ->
            settings.keepZoom = on
            photoView.keepZoomAcrossPhotos = on
        }
        swResume.setOnCheckedChangeListener { _, on -> settings.resume = on }

        findViewById<View>(R.id.rowSwipeSide).setOnClickListener {
            val labels = arrayOf("왼쪽으로 밀면 다음 사진", "왼쪽으로 밀면 이전 사진")
            choose("옆으로 밀기", labels, if (settings.swipeReverse) 1 else 0) { settings.swipeReverse = it == 1 }
        }
        val actions = listOf(SwipeAction.TRASH, SwipeAction.FAVORITE, SwipeAction.GRID, SwipeAction.NONE)
        val actionLabels = actions.map { it.label }.toTypedArray()
        findViewById<View>(R.id.rowSwipeUp).setOnClickListener {
            choose("위로 밀기", actionLabels, actions.indexOf(settings.swipeUp)) { settings.swipeUp = actions[it] }
        }
        findViewById<View>(R.id.rowSwipeDown).setOnClickListener {
            choose("아래로 밀기", actionLabels, actions.indexOf(settings.swipeDown)) { settings.swipeDown = actions[it] }
        }
        findViewById<View>(R.id.rowBlur).setOnClickListener {
            choose("흐린 사진 찾는 민감도", BLUR_LABELS, settings.blurSensitivity) {
                settings.blurSensitivity = it
                if (viewMode == ViewMode.BLURRY) applyFilters(null)
            }
        }
        findViewById<View>(R.id.rowManage).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) requestManageMedia()
        }
        tvUpdate = findViewById(R.id.tvUpdate)
        swAutoUpdate = findViewById(R.id.swAutoUpdate)
        findViewById<TextView>(R.id.tvCurrentVersion).text = "현재 버전 ${BuildConfig.VERSION_NAME}"
        findViewById<View>(R.id.rowUpdate).setOnClickListener { checkForUpdate(silent = false) }
        findViewById<View>(R.id.rowAutoUpdate).setOnClickListener { swAutoUpdate.toggle() }
        swAutoUpdate.setOnCheckedChangeListener { _, on -> settings.autoUpdate = on }
        findViewById<TextView>(R.id.tvVersion).text = try {
            "사진 정리 " + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "사진 정리"
        }
    }

    private fun choose(title: String, labels: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                onPick(which)
                d.dismiss()
                updateSettingsUi()
                updateHint()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openSettings() {
        if (selectionMode) setSelectionMode(false)
        showScreen(Screen.SETTINGS)
        updateSettingsUi()
    }

    private fun updateSettingsUi() {
        swKeepZoom.isChecked = settings.keepZoom
        swResume.isChecked = settings.resume
        swAutoUpdate.isChecked = settings.autoUpdate
        tvSwipeSide.text = if (settings.swipeReverse) "← 이전" else "← 다음"
        tvSwipeUp.text = settings.swipeUp.short.ifEmpty { "사용 안 함" }
        tvSwipeDown.text = settings.swipeDown.short.ifEmpty { "사용 안 함" }
        tvBlur.text = BLUR_LABELS[settings.blurSensitivity.coerceIn(0, 2)]
        tvManage.text = when {
            Build.VERSION.SDK_INT < 31 -> "지원 안 함"
            canManageMedia() -> "켜짐"
            else -> "꺼짐 · 켜기"
        }
    }

    // ============================================================ 업데이트

    /** 앱 켤 때: 자동 확인이 켜져 있고 12시간이 지났으면 조용히 확인 */
    private fun maybeAutoCheckUpdate() {
        if (!settings.autoUpdate) return
        if (System.currentTimeMillis() - settings.lastUpdateCheck < 12 * 60 * 60 * 1000L) return
        checkForUpdate(silent = true)
    }

    /** silent = true 면 새 버전이 있을 때만 알림 */
    private fun checkForUpdate(silent: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        tvUpdate.text = "확인 중…"
        io.execute {
            val result = runCatching { Updater.fetchLatest() }
            main.post {
                checkingUpdate = false
                if (isFinishing || isDestroyed) return@post
                val r = result.getOrNull()
                when {
                    result.isFailure -> {
                        tvUpdate.text = "확인하기"
                        if (!silent) Toast.makeText(this, "새 버전을 확인하지 못했어요. 인터넷 연결을 확인해 주세요", Toast.LENGTH_SHORT).show()
                    }
                    r != null && Updater.compareVersions(r.version, BuildConfig.VERSION_NAME) > 0 -> {
                        settings.lastUpdateCheck = System.currentTimeMillis()
                        tvUpdate.text = "새 버전 ${r.version}"
                        showUpdateDialog(r)
                    }
                    else -> {
                        settings.lastUpdateCheck = System.currentTimeMillis()
                        tvUpdate.text = "최신 버전"
                        if (!silent) Toast.makeText(this, "최신 버전을 쓰고 있어요", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(r: Updater.Release) {
        val notes = r.notes.lines().filter { it.isNotBlank() }.take(12).joinToString("\n")
        MaterialAlertDialogBuilder(this)
            .setTitle("새 버전 ${r.version}")
            .setMessage(buildString {
                append("지금 버전: ${BuildConfig.VERSION_NAME}\n")
                if (r.apkSize > 0) append("크기: ${Formatter.formatShortFileSize(this@MainActivity, r.apkSize)}\n")
                if (notes.isNotEmpty()) append("\n").append(notes)
            })
            .setPositiveButton("업데이트") { _, _ -> startUpdate(r) }
            .setNegativeButton("나중에", null)
            .show()
    }

    /** 내려받고 설치. 이 앱의 '출처를 알 수 없는 앱 설치'가 꺼져 있으면 먼저 켜도록 안내 */
    private fun startUpdate(r: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("설치 허용이 필요해요")
                .setMessage("새 버전을 설치하려면 '사진 정리'의 '출처를 알 수 없는 앱 설치'를 허용해 주세요.\n\n다음 화면에서 스위치를 켜고 돌아오면 바로 이어서 설치해요.")
                .setPositiveButton("허용하러 가기") { _, _ ->
                    pendingRelease = r
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        val dp = resources.displayMetrics.density
        val text = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text2))
            textSize = 14f
            text = "내려받는 중…"
        }
        val bar = LinearProgressIndicator(this).apply { isIndeterminate = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(text)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (14 * dp).toInt()
            })
        }
        var cancelled = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("새 버전 ${r.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("취소") { _, _ -> cancelled = true }
            .show()
        io.execute {
            var lastPost = 0L
            val result = runCatching {
                val file = Updater.download(this, r, { cancelled }) { got, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastPost > 150 || got == total) {
                        lastPost = now
                        main.post {
                            if (total > 0) {
                                bar.isIndeterminate = false
                                bar.max = 1000
                                bar.setProgressCompat((got * 1000 / total).toInt(), true)
                                text.text = "내려받는 중… ${Formatter.formatShortFileSize(this, got)} / ${Formatter.formatShortFileSize(this, total)}"
                            }
                        }
                    }
                }
                if (file != null) {
                    main.post { text.text = "설치 준비 중…"; bar.isIndeterminate = true }
                    Updater.install(this, file)
                }
                file
            }
            main.post {
                if (dialog.isShowing) dialog.dismiss()
                result.exceptionOrNull()?.let {
                    Toast.makeText(this, "업데이트하지 못했어요: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 설치 결과 (PackageInstaller 가 보내 줌) */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Updater.ACTION_INSTALL_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(this, "업데이트를 설치했어요", Toast.LENGTH_SHORT).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // 사용자가 취소
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "오류 $status"
                Toast.makeText(this, "설치하지 못했어요: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ============================================================ 결과 처리

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val ok = resultCode == RESULT_OK
        when (requestCode) {
            REQ_TRASH -> {
                val chunk = pendingTrash ?: return
                pendingTrash = null
                if (ok) {
                    trashedNow.addAll(chunk)
                    nextTrashChunk()
                } else {
                    trashQueue.clear() // 사용자가 취소하면 남은 것도 중단
                    finishTrash()
                }
            }
            REQ_RESTORE -> onRestoreResult(ok)
            REQ_FAVORITE -> onFavoriteResult(ok)
            REQ_BIN_RESTORE, REQ_BIN_DELETE -> onBinResult(ok)
        }
    }

    companion object {
        private const val THUMB = 320
        private const val BLUR_SIZE = 256
        private const val MIN_COLUMNS = 2
        private const val MAX_COLUMNS = 8
        private const val TRASH_CHUNK = 500
        private const val REQ_READ = 1
        private const val REQ_TRASH = 2
        private const val REQ_RESTORE = 3
        private const val REQ_BIN_RESTORE = 4
        private const val REQ_BIN_DELETE = 5
        private const val REQ_FAVORITE = 6
        private val BLUR_LABELS = arrayOf("낮음", "보통", "높음")
        private val DATE_FMT = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
    }
}
