package com.photoclean.app

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** 사진 보기: 사진이 위/아래 막대에 가리지 않는지 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ViewerLayoutTest {

    private fun layout(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY),
        )
        root.layout(root.left, root.top, root.right, root.bottom)
        ShadowLooper.idleMainLooper()
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun photoSitsBetweenBars() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        val cls = MainActivity::class.java
        val all = cls.getDeclaredField("allPhotos").apply { isAccessible = true }.get(a) as MutableList<Photo>
        repeat(5) { i -> all.add(Photo(10L + i, Uri.parse("content://media/external/images/media/${10 + i}"), "p$i.jpg", 1_762_000_000_000L - i, 1000)) }
        cls.getDeclaredMethod("applyFilters", java.lang.Long::class.java).apply { isAccessible = true }.invoke(a, null)
        cls.getDeclaredMethod("openViewer", Int::class.java).apply { isAccessible = true }.invoke(a, 0)
        val root = a.findViewById<View>(R.id.viewerScreen)
        layout(root); layout(root) // 막대 크기 확정 → 사진 자리 조정

        val photo = a.findViewById<View>(R.id.photo)
        val topCard = (a.findViewById<ViewGroup>(R.id.viewerTop)).getChildAt(0)
        val bottom = a.findViewById<View>(R.id.viewerBottom)
        assertTrue("사진 위(${photo.top})가 위쪽 막대 아래(${topCard.bottom}) 이후", photo.top >= topCard.bottom)
        assertTrue("사진 아래(${photo.bottom})가 아래쪽 판 위(${bottom.top}) 이전", photo.bottom <= bottom.top)
        assertTrue("사진 자리가 충분히 큼", photo.height > root.height / 2)

        // 한 번 탭 → 막대 숨김 → 사진이 화면 전체
        a.findViewById<ZoomImageView>(R.id.photo).onSingleTap!!.invoke()
        layout(root)
        assertEquals(0, photo.top)
        assertEquals(root.height, photo.bottom)
        controller.pause().stop().destroy()
    }
}
