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
    private val cache = LruCache<Long, Bitmap>(4)
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
        setupGridPinch()
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
        io.execute {
            val list = queryPhotos()
            main.post {
                photos.clear()
                photos.addAll(list)
                index = 0
                gridLoading.visibility = View.GONE
                showGrid()
            }
        }
    }

    private fun queryPhotos(): List<Photo> {
        val result = ArrayList<Photo>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
        )
        val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        contentResolver.query(collection, projection, null, null, sort)?.use { c ->
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
        if (photos.isEmpty()) showGridMessage("정리할 사진이 없어요 🎉") else gridEmpty.visibility = View.GONE
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
            gridTitle.text = "사진 정리"
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
        else if (selectionMode) setSelectionMode(false)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = photos[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? GridCell) ?: GridCell(this@MainActivity)
            val iv = cell.image
            val p = photos[position]
            cell.setSelectionState(selectionMode, p.id in selected)
            iv.tag = p.id
            val cached = thumbCache.get(p.id)
            if (cached != null) {
                iv.setImageBitmap(cached)
            } else {
                iv.setImageDrawable(null)
                thumbIo.execute {
                    // 스크롤로 이미 다른 사진에 재사용된 칸이면 건너뜀
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
            return cell
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

        displayImage(p)
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

    /** 화면 크기에 맞게 축소해서 디코딩 (EXIF 회전 자동 적용). */
    private fun decode(p: Photo): Bitmap? {
        cache.get(p.id)?.let { return it }
        return try {
            val maxSide = resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
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
        private val DATE_FMT = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
    }
}
