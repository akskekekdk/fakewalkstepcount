package com.photoclean.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Calendar

/**
 * 화면 모양 확인용 스크린샷 (검증은 하지 않음).
 * -Dscreenshots.dir=... 를 주면 그 폴더에 PNG 로 저장.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    private val dir = System.getProperty("screenshots.dir")?.let { File(it) }

    private fun shot(view: View, name: String) {
        val d = dir ?: return
        d.mkdirs()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY),
        )
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(b))
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun ms(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    @Suppress("UNCHECKED_CAST")
    @Test
    fun captureScreens() {
        if (dir == null) return
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        val root = a.window.decorView
        ShadowLooper.idleMainLooper()

        // 가짜 사진 30장 넣기 (썸네일은 회색 칸으로 보임)
        val all = MainActivity::class.java.getDeclaredField("allPhotos").apply { isAccessible = true }.get(a) as MutableList<Photo>
        val dates = (0 until 30).map { i -> if (i < 11) ms(2026, 11, 10 - i % 9) else if (i < 23) ms(2026, 10, 28 - i) else ms(2026, 9, 30 - i) }
        dates.forEachIndexed { i, d ->
            all.add(Photo(100L + i, Uri.parse("content://media/external/images/media/${100 + i}"), "IMG_$i.jpg", d, 3_400_000, "cam", "Camera", favorite = i % 7 == 2))
        }
        MainActivity::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(a, true)
        MainActivity::class.java.getDeclaredField("folders").apply { isAccessible = true }.set(a, PhotoLogic.groupFolders(all))
        MainActivity::class.java.getDeclaredMethod("applyFilters", java.lang.Long::class.java).apply { isAccessible = true }.invoke(a, null)
        MainActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(a)
        ShadowLooper.idleMainLooper()
        shot(root, "1_grid")

        a.findViewById<View>(R.id.btnSelect).performClick()
        ShadowLooper.idleMainLooper()
        shot(root, "2_select")
        a.findViewById<View>(R.id.btnSelCancel).performClick()

        // 한 장 보기: 그라데이션 그림을 사진 대신
        MainActivity::class.java.getDeclaredMethod("openViewer", Int::class.java).apply { isAccessible = true }.invoke(a, 2)
        ShadowLooper.idleMainLooper()
        val bmp = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 1200f, 1600f, 0xFF5B86E5.toInt(), 0xFFF0A35E.toInt(), Shader.TileMode.CLAMP)
        })
        a.findViewById<ZoomImageView>(R.id.photo).setImageBitmap(bmp)
        a.findViewById<View>(R.id.loading).visibility = View.GONE
        ShadowLooper.idleMainLooper()
        shot(root, "3_viewer")

        a.onBackPressedDispatcher.onBackPressed()
        a.findViewById<View>(R.id.btnSettings).performClick()
        ShadowLooper.idleMainLooper()
        shot(root, "4_settings")

        a.onBackPressedDispatcher.onBackPressed()
        a.findViewById<View>(R.id.btnOpenTrash).performClick()
        ShadowLooper.idleMainLooper()
        shot(root, "5_trash")
        controller.pause().stop().destroy()
    }
}
