package com.photoclean.app

import android.Manifest
import android.app.Activity
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
import android.view.GestureDetector
import android.view.MotionEvent
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

    private lateinit var photoView: ImageView
    private lateinit var counterText: TextView
    private lateinit var infoText: TextView
    private lateinit var emptyText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var manageBanner: TextView
    private lateinit var btnTrash: Button
    private lateinit var btnKeep: Button
    private lateinit var btnPrev: Button
    private lateinit var btnUndo: Button

    private val photos = mutableListOf<Photo>()
    private var index = 0

    /** 되돌리기용: (휴지통으로 보낸 사진, 원래 위치) */
    private val undoStack = ArrayDeque<Pair<Photo, Int>>()

    private var pendingTrash: Photo? = null
    private var pendingRestore: Pair<Photo, Int>? = null
    private var busy = false
    private var trashedCount = 0

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<Long, Bitmap>(4)
    private var loadToken = 0
    private var loaded = false

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
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
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
            showEmpty("사진 접근 권한이 필요해요.\n\n여기를 탭해서 다시 요청하거나\n설정 > 애플리케이션 > 사진 정리 > 권한에서 허용해 주세요.")
            emptyText.setOnClickListener {
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
        manageBanner.visibility =
            if (Build.VERSION.SDK_INT >= 31 && !canManageMedia()) View.VISIBLE else View.GONE
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
        loading.visibility = View.VISIBLE
        io.execute {
            val list = queryPhotos()
            main.post {
                photos.clear()
                photos.addAll(list)
                index = 0
                loading.visibility = View.GONE
                show()
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

    private fun showEmpty(msg: String) {
        photoView.setImageDrawable(null)
        emptyText.text = msg
        emptyText.visibility = View.VISIBLE
        counterText.text = if (trashedCount > 0) "휴지통으로 보낸 사진 ${trashedCount}장" else ""
        infoText.text = ""
        setButtonsEnabled(false)
    }

    private fun show() {
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
        val p = photos[index]
        pendingTrash = p
        busy = true
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, listOf(p.uri), true)
            // '미디어 관리' 권한이 있으면 확인창 없이 바로 RESULT_OK 로 돌아온다.
            startIntentSenderForResult(pi.intentSender, REQ_TRASH, null, 0, 0, 0)
        } catch (e: Exception) {
            busy = false
            pendingTrash = null
            Toast.makeText(this, "휴지통으로 보내지 못했어요: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun undo() {
        if (busy) return
        val last = undoStack.lastOrNull() ?: return
        pendingRestore = last
        busy = true
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, listOf(last.first.uri), false)
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
        busy = false
        when (requestCode) {
            REQ_TRASH -> {
                val p = pendingTrash ?: return
                pendingTrash = null
                if (resultCode == RESULT_OK) {
                    val pos = photos.indexOfFirst { it.id == p.id }
                    if (pos >= 0) {
                        photos.removeAt(pos)
                        undoStack.addLast(p to pos)
                        if (undoStack.size > 50) undoStack.removeFirst()
                        trashedCount++
                        // index 는 그대로 두면 자동으로 다음 사진을 가리킨다
                    }
                }
                show()
            }
            REQ_RESTORE -> {
                val (p, pos) = pendingRestore ?: return
                pendingRestore = null
                if (resultCode == RESULT_OK) {
                    undoStack.removeLastOrNull()
                    val at = pos.coerceIn(0, photos.size)
                    photos.add(at, p)
                    index = at
                    trashedCount = (trashedCount - 1).coerceAtLeast(0)
                    Toast.makeText(this, "복원했어요", Toast.LENGTH_SHORT).show()
                }
                show()
            }
        }
    }

    // ---------------------------------------------------------------- 스와이프

    private fun setupSwipe() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                val min = 120 * resources.displayMetrics.density / 2
                return when {
                    abs(dx) > abs(dy) && abs(dx) > min -> { move(if (dx < 0) +1 else -1); true }
                    dy < -min && abs(dy) > abs(dx) -> { trashCurrent(); true }
                    else -> false
                }
            }
        })
        photoView.setOnTouchListener { v, ev ->
            val handled = detector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            handled
        }
    }

    companion object {
        private const val REQ_READ = 1
        private const val REQ_TRASH = 2
        private const val REQ_RESTORE = 3
        private val DATE_FMT = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
    }
}
