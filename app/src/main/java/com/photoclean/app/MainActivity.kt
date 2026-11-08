package com.photoclean.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {

    private data class Photo(
        val id: Long,
        val uri: Uri,
        val name: String,
        val dateMillis: Long,
        val size: Long,
        /** 휴지통에 있는 사진만: 자동 영구 삭제 시각 (ms) */
        val expiresMillis: Long = 0,
        /** 즐겨찾기(하트) 여부 — 안드로이드 표준 즐겨찾기 값 */
        val favorite: Boolean = false,
    )

    private lateinit var photoView: ZoomImageView
    private lateinit var counterText: TextView
    private lateinit var infoText: TextView
    private lateinit var emptyText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var manageBanner: TextView
    private lateinit var btnTrash: Button
    private lateinit var btnKeep: Button
    private lateinit var btnPrev: Button
    private lateinit var btnUndo: Button
    private lateinit var btnBack: Button
    private lateinit var gridScreen: View
    private lateinit var viewerScreen: View
    private lateinit var grid: GridView
    private lateinit var gridCount: TextView
    private lateinit var gridEmpty: TextView
    private lateinit var gridLoading: ProgressBar
    private lateinit var gridBanner: TextView
    private lateinit var gridTitle: TextView
    private lateinit var btnSelect: Button
    private lateinit var btnSelCancel: Button
    private lateinit var btnGridUndo: Button
    private lateinit var selBar: View
    private lateinit var btnTrashSelected: Button

    /** 전체 보기 선택 모드 */
    private var selectionMode = false
    private val selected = LinkedHashSet<Long>()
    private var columns = 4
    private val gridAdapter = GridAdapter()
    private lateinit var strip: RecyclerView
    private lateinit var stripLayout: LinearLayoutManager
    private val stripAdapter = StripAdapter()

    /** true 면 즐겨찾기 사진만 보기 */
    private var favOnly = false
    private lateinit var tabAll: Button
    private lateinit var tabFav: Button
    private lateinit var btnFav: Button
    private var pendingFav: Pair<Photo, Boolean>? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    // 휴지통 화면
    private lateinit var trashScreen: View
    private lateinit var trashGrid: GridView
    private lateinit var trashTitle: TextView
    private lateinit var trashEmpty: TextView
    private lateinit var trashLoading: ProgressBar
    private lateinit var btnTrashSelectAll: Button
    private lateinit var btnRestoreSelected: Button
    private lateinit var btnDeleteForever: Button
    private val trashed = mutableListOf<Photo>()
    private val trashSelected = LinkedHashSet<Long>()
    private val trashAdapter = TrashAdapter()
    /** 휴지통 화면에서 진행 중인 복원/영구삭제 요청 (많으면 나눠서) */
    private val binQueue = ArrayDeque<List<Photo>>()
    private var binRequestCode = 0
    private var binDone = 0
    private val thumbIo = Executors.newFixedThreadPool(3)
    private val thumbCache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    private val photos = mutableListOf<Photo>()
    private var index = 0

    /** 되돌리기용: 한 번에 지운 묶음 = [(사진, 원래 위치)] (위치 오름차순) */
    private val undoStack = ArrayDeque<List<Pair<Photo, Int>>>()

    /** 휴지통으로 보내는 중인 사진들 (많으면 나눠서 요청) */
    private val trashQueue = ArrayDeque<List<Photo>>()
    private val trashBatch = ArrayList<Pair<Photo, Int>>()
    private var pendingTrash: List<Photo>? = null
    private var pendingRestore: List<Pair<Photo, Int>>? = null
    private var busy = false
    private var trashedCount = 0

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    /** 크게 보기용 사진 캐시 (확대해도 선명하도록 화면보다 크게 디코딩하므로 용량 기준) */
    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private var loadToken = 0
    private var loaded = false
    private var askedManageThisSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        photoView = findViewById(R.id.photo)
        counterText = findViewById(R.id.counter)
        infoText = findViewById(R.id.info)
        emptyText = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        manageBanner = findViewById(R.id.manageBanner)
        btnTrash = findViewById(R.id.btnTrash)
        btnKeep = findViewById(R.id.btnKeep)
        btnPrev = findViewById(R.id.btnPrev)
        btnUndo = findViewById(R.id.btnUndo)
        btnBack = findViewById(R.id.btnBack)
        gridScreen = findViewById(R.id.gridScreen)
        viewerScreen = findViewById(R.id.viewerScreen)
        grid = findViewById(R.id.grid)
        gridCount = findViewById(R.id.gridCount)
        gridEmpty = findViewById(R.id.gridEmpty)
        gridLoading = findViewById(R.id.gridLoading)
        gridBanner = findViewById(R.id.gridBanner)
        gridTitle = findViewById(R.id.gridTitle)
        btnSelect = findViewById(R.id.btnSelect)
        btnSelCancel = findViewById(R.id.btnSelCancel)
        btnGridUndo = findViewById(R.id.btnGridUndo)
        selBar = findViewById(R.id.selBar)
        btnTrashSelected = findViewById(R.id.btnTrashSelected)

        columns = getPreferences(MODE_PRIVATE).getInt("columns", 4)
        grid.numColumns = columns
        grid.adapter = gridAdapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            if (selectionMode) toggleSelect(pos) else openViewer(pos)
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            if (!selectionMode) setSelectionMode(true)
            toggleSelect(pos)
            true
        }
        btnSelect.setOnClickListener {
            if (!selectionMode) setSelectionMode(true) else toggleSelectAll()
        }
        btnSelCancel.setOnClickListener { setSelectionMode(false) }
        btnTrashSelected.setOnClickListener { trashSelected() }
        btnGridUndo.setOnClickListener { undo() }
        setupTrashScreen()

        tabAll = findViewById(R.id.tabAll)
        tabFav = findViewById(R.id.tabFav)
        btnFav = findViewById(R.id.btnFav)
        tabAll.setOnClickListener { setFavOnly(false) }
        tabFav.setOnClickListener { setFavOnly(true) }
        btnFav.setOnClickListener { toggleFavorite() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        photoView.keepZoomAcrossPhotos = prefs.getBoolean(PREF_KEEP_ZOOM, true)
        updateTabs()
        setupGridPinch()

        strip = findViewById(R.id.strip)
        stripLayout = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        strip.layoutManager = stripLayout
        strip.adapter = stripAdapter
        strip.itemAnimator = null
        btnBack.setOnClickListener { showGrid() }
        gridBanner.setOnClickListener { requestManageMedia() }

        btnTrash.setOnClickListener { trashCurrent() }
        btnKeep.setOnClickListener { move(+1) }
        btnPrev.setOnClickListener { move(-1) }
        btnUndo.setOnClickListener { undo() }
        manageBanner.setOnClickListener { requestManageMedia() }

        setupSwipe()

        if (hasReadPermission()) loadPhotos() else requestReadPermission()
    }

    override fun onResume() {
        super.onResume()
        updateManageBanner()
        if (!loaded && hasReadPermission()) loadPhotos()
        if (hasReadPermission() && !askedManageThisSession) {
            askedManageThisSession = true
            if (Build.VERSION.SDK_INT >= 31 && !canManageMedia()) showManageMediaDialog(null)
        }
    }

    /** 확인창 없이 바로 지우려면 '미디어 관리' 권한이 필요하다고 안내. */
    private fun showManageMediaDialog(onSkip: (() -> Unit)?) {
        val b = AlertDialog.Builder(this)
            .setTitle("버튼 한 번에 바로 삭제")
            .setMessage(
                "휴지통 버튼을 누를 때마다 뜨는 확인창을 없애려면\n" +
                    "'미디어 관리' 권한을 켜주세요.\n\n" +
                    "다음 화면에서 '사진 정리'의 스위치를 켜고 돌아오면 됩니다."
            )
            .setPositiveButton("권한 켜기") { _, _ -> requestManageMedia() }
        if (onSkip != null) b.setNegativeButton("이번만 그냥 삭제") { _, _ -> onSkip() }
        else b.setNegativeButton("나중에", null)
        b.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        thumbIo.shutdownNow()
    }

    // ---------------------------------------------------------------- 권한

    private fun readPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasReadPermission(): Boolean = readPermissions().any { granted(it) }

    private fun requestReadPermission() = requestPermissions(readPermissions(), REQ_READ)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_READ) return
        if (hasReadPermission()) {
            loadPhotos()
        } else {
            showGridMessage("사진 접근 권한이 필요해요.\n\n여기를 탭해서 다시 요청하거나\n설정 > 애플리케이션 > 사진 정리 > 권한에서 허용해 주세요.")
            gridEmpty.setOnClickListener {
                if (shouldShowRequestPermissionRationale(readPermissions()[0])) {
                    requestReadPermission()
                } else {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
            }
        }
    }

    /** '미디어 관리' 권한이 있으면 휴지통 이동 시 시스템 확인창이 뜨지 않는다. */
    private fun canManageMedia(): Boolean =
        Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(this)

    private fun updateManageBanner() {
        val v = if (Build.VERSION.SDK_INT >= 31 && !canManageMedia()) View.VISIBLE else View.GONE
        manageBanner.visibility = v
        gridBanner.visibility = v
    }

    private fun requestManageMedia() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // ---------------------------------------------------------------- 사진 목록

    private fun loadPhotos() {
        loaded = true
        emptyText.setOnClickListener(null)
        gridEmpty.setOnClickListener(null)
        gridLoading.visibility = View.VISIBLE
        val favFilter = favOnly
        io.execute {
            val list = queryPhotos(favoritesOnly = favFilter)
            main.post {
                if (favFilter != favOnly) return@post // 그 사이 탭이 바뀜
                photos.clear()
                photos.addAll(list)
                index = 0
                gridLoading.visibility = View.GONE
                showGrid()
            }
        }
    }

    /** trashedOnly = true 면 휴지통에 있는 사진만, 최근에 지운 순서로. */
    private fun queryPhotos(trashedOnly: Boolean = false, favoritesOnly: Boolean = false): List<Photo> {
        val result = ArrayList<Photo>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.IS_FAVORITE,
        )
        val sort = if (trashedOnly) {
            // 휴지통 기한 = 지운 시각 + 30일 → 기한이 늦을수록 최근에 지운 것
            "${MediaStore.MediaColumns.DATE_EXPIRES} DESC"
        } else {
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        }
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
            if (trashedOnly) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            if (favoritesOnly) {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.IS_FAVORITE} = 1")
            }
        }
        val cursor = try {
            contentResolver.query(collection, projection, args, null)
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            val expCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
            val favCol = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val taken = c.getLong(takenCol)
                val date = if (taken > 0) taken else c.getLong(addedCol) * 1000
                result.add(
                    Photo(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "",
                        dateMillis = date,
                        size = c.getLong(sizeCol),
                        expiresMillis = if (expCol >= 0) c.getLong(expCol) * 1000 else 0,
                        favorite = favCol >= 0 && c.getInt(favCol) == 1,
                    )
                )
            }
        }
        return result
    }

    // ---------------------------------------------------------------- 화면 표시

    // ---------------------------------------------------------------- 전체 보기(그리드)

    private fun showGridMessage(msg: String) {
        gridEmpty.text = msg
        gridEmpty.visibility = View.VISIBLE
    }

    private fun showGrid() {
        viewerScreen.visibility = View.GONE
        gridScreen.visibility = View.VISIBLE
        updateGridHeader()
        if (photos.isEmpty()) {
            showGridMessage(
                if (favOnly) "즐겨찾기한 사진이 없어요\n\n사진을 크게 연 뒤 위쪽 ♡ 를 누르면\n즐겨찾기에 추가돼요"
                else "정리할 사진이 없어요 🎉"
            )
        } else gridEmpty.visibility = View.GONE
        if (photos.isNotEmpty()) {
            val pos = index.coerceIn(0, photos.size - 1)
            if (pos < grid.firstVisiblePosition || pos > grid.lastVisiblePosition) grid.setSelection(pos)
        }
    }

    private fun updateGridHeader() {
        gridAdapter.notifyDataSetChanged()
        val undoOn = undoStack.isNotEmpty() && !busy
        btnGridUndo.isEnabled = undoOn
        btnGridUndo.visibility = if (undoStack.isNotEmpty() && !selectionMode) View.VISIBLE else View.GONE
        if (selectionMode) {
            gridTitle.text = "${selected.size}장 선택"
            gridCount.text = "탭해서 고르기 · 길게 눌러도 선택"
            btnSelect.text = if (selected.size == photos.size && photos.isNotEmpty()) "전체 해제" else "전체"
            btnSelCancel.visibility = View.VISIBLE
            selBar.visibility = View.VISIBLE
            btnTrashSelected.text = if (selected.isEmpty()) "사진을 골라주세요" else "🗑 ${selected.size}장 휴지통으로"
            btnTrashSelected.isEnabled = selected.isNotEmpty() && !busy
            btnTrashSelected.alpha = if (btnTrashSelected.isEnabled) 1f else 0.4f
        } else {
            gridTitle.text = if (favOnly) "♥ 즐겨찾기" else "사진 정리"
            gridCount.text = buildString {
                append("${photos.size}장")
                if (trashedCount > 0) append("  ·  🗑 $trashedCount")
                append("  ·  두 손가락으로 크기 조절")
            }
            btnSelect.text = "선택"
            btnSelCancel.visibility = View.GONE
            selBar.visibility = View.GONE
        }
    }

    private fun setSelectionMode(on: Boolean) {
        selectionMode = on
        if (!on) selected.clear()
        updateGridHeader()
    }

    private fun toggleSelect(pos: Int) {
        val id = photos.getOrNull(pos)?.id ?: return
        if (!selected.remove(id)) selected.add(id)
        updateGridHeader()
    }

    private fun toggleSelectAll() {
        if (selected.size == photos.size) selected.clear()
        else photos.forEach { selected.add(it.id) }
        updateGridHeader()
    }

    /** 전체 보기에서 두 손가락으로 벌리면 크게(칸 수 ↓), 오므리면 작게(칸 수 ↑). */
    private fun setupGridPinch() {
        var acc = 1f
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                val next = when {
                    acc > 1.25f -> columns - 1
                    acc < 0.8f -> columns + 1
                    else -> return true
                }.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                acc = 1f
                if (next != columns) {
                    val first = grid.firstVisiblePosition
                    columns = next
                    grid.numColumns = columns
                    grid.setSelection(first)
                    getPreferences(MODE_PRIVATE).edit().putInt("columns", columns).apply()
                }
                return true
            }
        })
        var pinching = false
        grid.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
            if (!pinching && ev.pointerCount > 1) {
                // 두 손가락이 닿는 순간 그리드의 스크롤/탭 처리를 취소
                pinching = true
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                grid.onTouchEvent(cancel)
                cancel.recycle()
            }
            // 핀치 중엔 손을 뗄 때까지 이벤트를 그리드에 넘기지 않음 (사진이 눌리지 않게)
            pinching
        }
    }

    private fun openViewer(pos: Int) {
        if (pos !in photos.indices) return
        index = pos
        gridScreen.visibility = View.GONE
        viewerScreen.visibility = View.VISIBLE
        show()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (viewerScreen.visibility == View.VISIBLE) showGrid()
        else if (trashScreen.visibility == View.VISIBLE) closeTrashScreen()
        else if (selectionMode) setSelectionMode(false)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = photos[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? GridCell) ?: GridCell(this@MainActivity)
            val p = photos[position]
            cell.setSelectionState(selectionMode, p.id in selected)
            cell.setLabel(null)
            cell.setFavorite(p.favorite)
            loadThumb(cell.image, p)
            return cell
        }
    }

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

    // ---------------------------------------------------------------- 즐겨찾기

    private fun updateTabs() {
        tabAll.alpha = if (favOnly) 0.45f else 1f
        tabFav.alpha = if (favOnly) 1f else 0.45f
        tabAll.setTypeface(null, if (favOnly) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        tabFav.setTypeface(null, if (favOnly) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /** 전체 ↔ 즐겨찾기만 보기 전환 */
    private fun setFavOnly(on: Boolean) {
        if (favOnly == on || busy) return
        if (selectionMode) setSelectionMode(false)
        favOnly = on
        // 목록이 바뀌므로 '되돌리기' 위치 기록은 맞지 않음 (지운 사진은 휴지통 화면에서 복원 가능)
        undoStack.clear()
        updateTabs()
        // 새 목록이 올 때까지 이전 목록이 보이지 않게 비우고 제목은 바로 바꾼다
        photos.clear()
        index = 0
        updateGridHeader()
        if (hasReadPermission()) loadPhotos()
    }

    /** 지금 보는 사진의 하트 켜기/끄기 */
    private fun toggleFavorite() {
        if (photos.isEmpty() || busy) return
        val p = photos[index]
        val value = !p.favorite
        pendingFav = p to value
        busy = true
        try {
            // '미디어 관리' 권한이 있으면 확인창 없이 바로 처리된다
            val pi = MediaStore.createFavoriteRequest(contentResolver, listOf(p.uri), value)
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
            val pos = photos.indexOfFirst { it.id == p.id }
            // 즐겨찾기 화면에서 하트를 꺼도 바로 사라지지 않게 목록에는 남겨둔다 (탭을 다시 누르면 정리됨)
            if (pos >= 0) photos[pos] = photos[pos].copy(favorite = value)
            Toast.makeText(this, if (value) "♥ 즐겨찾기에 추가했어요" else "즐겨찾기에서 뺐어요", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    // ---------------------------------------------------------------- 설정

    private fun showSettings() {
        val labels = arrayOf("확대 유지: 다음 사진에서도 같은 부분을 확대해서 보기")
        val checked = booleanArrayOf(photoView.keepZoomAcrossPhotos)
        AlertDialog.Builder(this)
            .setTitle("설정")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("확인") { _, _ ->
                photoView.keepZoomAcrossPhotos = checked[0]
                prefs.edit().putBoolean(PREF_KEEP_ZOOM, checked[0]).apply()
                Toast.makeText(
                    this,
                    if (checked[0]) "확대 유지를 켰어요" else "확대 유지를 껐어요",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------------------------------------------------------- 휴지통 화면

    private fun setupTrashScreen() {
        trashScreen = findViewById(R.id.trashScreen)
        trashGrid = findViewById(R.id.trashGrid)
        trashTitle = findViewById(R.id.trashTitle)
        trashEmpty = findViewById(R.id.trashEmpty)
        trashLoading = findViewById(R.id.trashLoading)
        btnTrashSelectAll = findViewById(R.id.btnTrashSelectAll)
        btnRestoreSelected = findViewById(R.id.btnRestoreSelected)
        btnDeleteForever = findViewById(R.id.btnDeleteForever)

        trashGrid.adapter = trashAdapter
        trashGrid.setOnItemClickListener { _, _, pos, _ ->
            val id = trashed.getOrNull(pos)?.id ?: return@setOnItemClickListener
            if (!trashSelected.remove(id)) trashSelected.add(id)
            updateTrashHeader()
        }
        findViewById<View>(R.id.btnOpenTrash).setOnClickListener { openTrashScreen() }
        findViewById<View>(R.id.btnTrashBack).setOnClickListener { closeTrashScreen() }
        btnTrashSelectAll.setOnClickListener {
            if (trashSelected.size == trashed.size) trashSelected.clear()
            else trashed.forEach { trashSelected.add(it.id) }
            updateTrashHeader()
        }
        btnRestoreSelected.setOnClickListener { restoreFromBin() }
        btnDeleteForever.setOnClickListener { confirmDeleteForever() }
    }

    private fun openTrashScreen() {
        if (selectionMode) setSelectionMode(false)
        gridScreen.visibility = View.GONE
        trashScreen.visibility = View.VISIBLE
        trashGrid.numColumns = columns
        loadTrash()
    }

    private fun closeTrashScreen() {
        trashScreen.visibility = View.GONE
        trashSelected.clear()
        showGrid()
    }

    private fun loadTrash() {
        trashLoading.visibility = View.VISIBLE
        trashEmpty.visibility = View.GONE
        io.execute {
            val list = if (hasReadPermission()) queryPhotos(trashedOnly = true) else emptyList()
            main.post {
                trashed.clear()
                trashed.addAll(list)
                trashSelected.retainAll(list.map { it.id }.toSet())
                trashLoading.visibility = View.GONE
                updateTrashHeader()
            }
        }
    }

    private fun updateTrashHeader() {
        trashAdapter.notifyDataSetChanged()
        trashEmpty.visibility = if (trashed.isEmpty()) View.VISIBLE else View.GONE
        trashTitle.text = when {
            trashSelected.isNotEmpty() -> "${trashSelected.size}장 선택"
            else -> "휴지통 ${trashed.size}장"
        }
        btnTrashSelectAll.text = if (trashed.isNotEmpty() && trashSelected.size == trashed.size) "전체 해제" else "전체"
        btnTrashSelectAll.isEnabled = trashed.isNotEmpty()
        val on = trashSelected.isNotEmpty() && !busy
        btnRestoreSelected.isEnabled = on
        btnDeleteForever.isEnabled = on
        btnRestoreSelected.alpha = if (on) 1f else 0.4f
        btnDeleteForever.alpha = if (on) 1f else 0.4f
        btnRestoreSelected.text = if (trashSelected.isEmpty()) "↶ 복원" else "↶ ${trashSelected.size}장 복원"
        btnDeleteForever.text = if (trashSelected.isEmpty()) "영구 삭제" else "${trashSelected.size}장 영구 삭제"
    }

    /** 자동 영구 삭제까지 남은 날 */
    private fun daysLeftLabel(p: Photo): String? {
        if (p.expiresMillis <= 0) return null
        val left = p.expiresMillis - System.currentTimeMillis()
        if (left <= 0) return "곧 삭제"
        val days = Math.ceil(left / 86_400_000.0).toInt()
        return if (days <= 1) "1일 남음" else "${days}일 남음"
    }

    private fun selectedInBin(): List<Photo> = trashed.filter { it.id in trashSelected }

    private fun restoreFromBin() {
        val list = selectedInBin()
        if (list.isEmpty() || busy) return
        startBinRequests(REQ_BIN_RESTORE, list)
    }

    private fun confirmDeleteForever() {
        val list = selectedInBin()
        if (list.isEmpty() || busy) return
        AlertDialog.Builder(this)
            .setTitle("${list.size}장을 영구 삭제할까요?")
            .setMessage("영구 삭제한 사진은 되돌릴 수 없어요.")
            .setPositiveButton("영구 삭제") { _, _ -> startBinRequests(REQ_BIN_DELETE, list) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startBinRequests(code: Int, list: List<Photo>) {
        binQueue.clear()
        list.chunked(TRASH_CHUNK).forEach { binQueue.addLast(it) }
        binRequestCode = code
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
            val pi = if (binRequestCode == REQ_BIN_RESTORE) {
                MediaStore.createTrashRequest(contentResolver, uris, false)
            } else {
                MediaStore.createDeleteRequest(contentResolver, uris)
            }
            startIntentSenderForResult(pi.intentSender, binRequestCode, null, 0, 0, 0)
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
            nextBinRequest()
        } else {
            binQueue.clear()
            finishBinRequests()
        }
    }

    private fun finishBinRequests() {
        busy = false
        if (binDone > 0) {
            val msg = if (binRequestCode == REQ_BIN_RESTORE) "${binDone}장 복원했어요" else "${binDone}장 영구 삭제했어요"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            // 휴지통 밖에서 바뀐 것이므로 '되돌리기' 기록은 더 이상 맞지 않음
            undoStack.clear()
            trashSelected.clear()
            if (binRequestCode == REQ_BIN_RESTORE) reloadPhotosKeepingState()
        }
        binDone = 0
        loadTrash()
    }

    /** 복원된 사진이 전체 보기에 다시 보이도록 목록 새로 고침. */
    private fun reloadPhotosKeepingState() {
        val favFilter = favOnly
        io.execute {
            val list = queryPhotos(favoritesOnly = favFilter)
            main.post {
                if (favFilter != favOnly) return@post
                val currentId = photos.getOrNull(index)?.id
                photos.clear()
                photos.addAll(list)
                index = photos.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                gridAdapter.notifyDataSetChanged()
            }
        }
    }

    private inner class TrashAdapter : BaseAdapter() {
        override fun getCount() = trashed.size
        override fun getItem(position: Int) = trashed[position]
        override fun getItemId(position: Int) = trashed[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? GridCell) ?: GridCell(this@MainActivity)
            val p = trashed[position]
            cell.setSelectionState(true, p.id in trashSelected)
            cell.setLabel(daysLeftLabel(p))
            cell.setFavorite(false)
            loadThumb(cell.image, p)
            return cell
        }
    }

    // ---------------------------------------------------------------- 썸네일 줄 (한 장 보기 화면)

    private class StripHolder(val frame: android.widget.FrameLayout, val image: ImageView) :
        RecyclerView.ViewHolder(frame)

    private inner class StripAdapter : RecyclerView.Adapter<StripHolder>() {
        private val dp by lazy { resources.displayMetrics.density }

        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StripHolder {
            val size = (56 * dp).toInt()
            val frame = android.widget.FrameLayout(this@MainActivity).apply {
                layoutParams = RecyclerView.LayoutParams(size, size).apply {
                    val m = (2 * dp).toInt()
                    setMargins(m, 0, m, 0)
                }
                setPadding((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt())
            }
            val iv = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF222222.toInt())
            }
            frame.addView(iv, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            val h = StripHolder(frame, iv)
            frame.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && !busy) {
                    index = pos
                    show()
                }
            }
            return h
        }

        override fun onBindViewHolder(h: StripHolder, position: Int) {
            val p = photos[position]
            val current = position == index
            // 지금 보는 사진은 빨간 테두리
            h.frame.setBackgroundColor(if (current) 0xFFE53935.toInt() else 0x00000000)
            h.image.alpha = if (current) 1f else 0.6f
            loadThumb(h.image, p)
        }
    }

    /** 지금 사진이 썸네일 줄 가운데 오도록 스크롤. */
    private fun centerStrip() {
        stripAdapter.notifyDataSetChanged()
        if (photos.isEmpty()) return
        strip.post {
            val item = (60 * resources.displayMetrics.density).toInt()
            stripLayout.scrollToPositionWithOffset(index, (strip.width - item) / 2)
        }
    }

    private fun showEmpty(msg: String) {
        photoView.setImageDrawable(null)
        emptyText.text = msg
        emptyText.visibility = View.VISIBLE
        counterText.text = if (trashedCount > 0) "휴지통으로 보낸 사진 ${trashedCount}장" else ""
        infoText.text = ""
        setButtonsEnabled(false)
    }

    private fun show() {
        gridAdapter.notifyDataSetChanged()
        btnUndo.isEnabled = undoStack.isNotEmpty() && !busy
        btnUndo.alpha = if (btnUndo.isEnabled) 1f else 0.35f

        if (photos.isEmpty()) {
            showEmpty("정리할 사진이 없어요 🎉")
            stripAdapter.notifyDataSetChanged()
            return
        }
        index = index.coerceIn(0, photos.size - 1)
        emptyText.visibility = View.GONE
        setButtonsEnabled(true)
        btnPrev.isEnabled = index > 0
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else 0.35f

        val p = photos[index]
        counterText.text = buildString {
            append("${index + 1} / ${photos.size}")
            if (trashedCount > 0) append("   ·   🗑 $trashedCount")
        }
        infoText.text = "${DATE_FMT.format(Date(p.dateMillis))}  ·  ${Formatter.formatShortFileSize(this, p.size)}"

        btnFav.text = if (p.favorite) "♥" else "♡"
        btnFav.setTextColor(if (p.favorite) 0xFFFF5370.toInt() else 0xFFFFFFFF.toInt())
        displayImage(p)
        centerStrip()
        // 다음 사진 미리 불러오기
        photos.getOrNull(index + 1)?.let { prefetch(it) }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(btnTrash, btnKeep, btnPrev).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.35f
        }
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
                else infoText.text = "${infoText.text}  ·  (미리보기 불가)"
            }
        }
    }

    private fun prefetch(p: Photo) {
        if (cache.get(p.id) != null) return
        io.execute { decode(p) }
    }

    /** 화면에 맞게 축소해서 디코딩 (EXIF 회전 자동 적용). */
    private fun decode(p: Photo): Bitmap? {
        cache.get(p.id)?.let { return it }
        return try {
            // 확대했을 때도 선명하게: 화면의 2배(최대 4096px)까지 디코딩
            val maxSide = resources.displayMetrics.let { minOf(maxOf(it.widthPixels, it.heightPixels) * 2, 4096) }
            val src = ImageDecoder.createSource(contentResolver, p.uri)
            val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) {
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
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

    // ---------------------------------------------------------------- 휴지통

    private fun trashCurrent() {
        if (photos.isEmpty() || busy) return
        requestTrash(listOf(photos[index]))
    }

    private fun trashSelected() {
        if (busy || selected.isEmpty()) return
        val list = photos.filter { it.id in selected }
        if (list.isEmpty()) return
        requestTrash(list)
    }

    /** 미디어 관리 권한이 없으면 먼저 안내한 뒤 휴지통 요청. */
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
        trashBatch.clear()
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
            val pi = MediaStore.createTrashRequest(contentResolver, chunk.map { it.uri }, true)
            // '미디어 관리' 권한이 있으면 확인창 없이 바로 RESULT_OK 로 돌아온다.
            startIntentSenderForResult(pi.intentSender, REQ_TRASH, null, 0, 0, 0)
        } catch (e: Exception) {
            pendingTrash = null
            trashQueue.clear()
            Toast.makeText(this, "휴지통으로 보내지 못했어요: ${e.message}", Toast.LENGTH_SHORT).show()
            finishTrash()
        }
    }

    /** 실제로 휴지통에 간 사진들을 목록에서 빼고 되돌리기 묶음으로 저장. */
    private fun finishTrash() {
        busy = false
        if (trashBatch.isNotEmpty()) {
            // 원래 위치를 기록하고 뒤에서부터 지워야 위치가 안 꼬인다
            val withPos = trashBatch.map { (p, _) -> p to photos.indexOfFirst { it.id == p.id } }
                .filter { it.second >= 0 }
                .sortedBy { it.second }
            withPos.asReversed().forEach { (p, pos) ->
                photos.removeAt(pos)
                selected.remove(p.id)
                thumbCache.remove(p.id)
                cache.remove(p.id)
            }
            if (withPos.isNotEmpty()) {
                undoStack.addLast(withPos)
                if (undoStack.size > 50) undoStack.removeFirst()
                trashedCount += withPos.size
                if (withPos.size > 1) {
                    Toast.makeText(this, "${withPos.size}장을 휴지통으로 보냈어요", Toast.LENGTH_SHORT).show()
                }
            }
            trashBatch.clear()
        }
        if (selectionMode && gridScreen.visibility == View.VISIBLE) {
            setSelectionMode(false)
        }
        refresh()
    }

    /** 지금 보이는 화면을 다시 그린다. */
    private fun refresh() {
        if (viewerScreen.visibility == View.VISIBLE) show() else showGrid()
    }

    private fun undo() {
        if (busy) return
        val last = undoStack.lastOrNull() ?: return
        pendingRestore = last
        busy = true
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, last.map { it.first.uri }, false)
            startIntentSenderForResult(pi.intentSender, REQ_RESTORE, null, 0, 0, 0)
        } catch (e: Exception) {
            busy = false
            pendingRestore = null
            Toast.makeText(this, "되돌리지 못했어요: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_BIN_RESTORE, REQ_BIN_DELETE -> onBinResult(resultCode == RESULT_OK)
            REQ_FAVORITE -> onFavoriteResult(resultCode == RESULT_OK)
            REQ_TRASH -> {
                val chunk = pendingTrash ?: return
                pendingTrash = null
                if (resultCode == RESULT_OK) {
                    chunk.forEach { trashBatch.add(it to -1) }
                    nextTrashChunk()
                } else {
                    trashQueue.clear() // 사용자가 취소하면 남은 것도 중단
                    finishTrash()
                }
            }
            REQ_RESTORE -> {
                busy = false
                val batch = pendingRestore ?: return
                pendingRestore = null
                if (resultCode == RESULT_OK) {
                    undoStack.removeLastOrNull()
                    // 원래 위치 오름차순으로 다시 끼워 넣으면 제자리로 돌아간다
                    batch.forEach { (p, pos) -> photos.add(pos.coerceIn(0, photos.size), p) }
                    index = batch.first().second.coerceIn(0, photos.size - 1)
                    trashedCount = (trashedCount - batch.size).coerceAtLeast(0)
                    Toast.makeText(this, "${batch.size}장 복원했어요", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
        }
    }

    // ---------------------------------------------------------------- 스와이프

    /** 확대하지 않은 상태에서: ← 다음, → 이전, ↑ 휴지통 */
    private fun setupSwipe() {
        val hint = findViewById<TextView>(R.id.hint)
        val normalHint = hint.text
        photoView.onZoomChanged = { zoomed ->
            if (zoomed && photoView.keepZoomAcrossPhotos) {
                hint.text = "🔍 확대 유지 중 — 다음 사진도 같은 부분이 확대돼요 · 더블탭: 해제"
                hint.setTextColor(0xFFFFB300.toInt())
            } else {
                hint.text = normalHint
                hint.setTextColor(0xFF777777.toInt())
            }
        }
        photoView.onSwipe = { dx, dy ->
            val min = 60 * resources.displayMetrics.density
            when {
                abs(dx) > abs(dy) && abs(dx) > min -> move(if (dx < 0) +1 else -1)
                dy < -min && abs(dy) > abs(dx) -> trashCurrent()
            }
        }
    }

    companion object {
        private const val THUMB = 320
        private const val MIN_COLUMNS = 2
        private const val MAX_COLUMNS = 8
        private const val TRASH_CHUNK = 500
        private const val REQ_READ = 1
        private const val REQ_TRASH = 2
        private const val REQ_RESTORE = 3
        private const val REQ_BIN_RESTORE = 4
        private const val REQ_BIN_DELETE = 5
        private const val REQ_FAVORITE = 6
        private const val PREF_KEEP_ZOOM = "keep_zoom"
        private val DATE_FMT = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
    }
}
