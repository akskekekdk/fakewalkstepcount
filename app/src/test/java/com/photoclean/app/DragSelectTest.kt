package com.photoclean.app

import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** 실제처럼: 사진을 길게 누른 뒤 손가락을 끌어 여러 장 선택 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DragSelectTest {

    @Suppress("UNCHECKED_CAST")
    @Test
    fun longPressThenDragSelectsRange() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        val cls = MainActivity::class.java
        val all = cls.getDeclaredField("allPhotos").apply { isAccessible = true }.get(a) as MutableList<Photo>
        // 같은 달 사진 20장 (머리말 1줄 + 4칸씩 5줄)
        repeat(20) { i ->
            all.add(Photo(1000L + i, Uri.parse("content://media/external/images/media/${1000 + i}"), "p$i.jpg", 1_762_000_000_000L - i * 60_000L, 1000))
        }
        cls.getDeclaredMethod("applyFilters", java.lang.Long::class.java).apply { isAccessible = true }.invoke(a, null)
        val grid = a.findViewById<RecyclerView>(R.id.grid)
        grid.measure(View.MeasureSpec.makeMeasureSpec(grid.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(grid.height, View.MeasureSpec.EXACTLY))
        grid.layout(grid.left, grid.top, grid.right, grid.bottom)
        ShadowLooper.idleMainLooper()

        // 어댑터 위치 1 = 첫 사진(0번), 위치 0 은 월 머리말
        val first = grid.findViewHolderForAdapterPosition(1)!!.itemView
        val target = grid.findViewHolderForAdapterPosition(1 + 6)!!.itemView // 6번 사진 (둘째 줄 셋째 칸)
        val t0 = SystemClock.uptimeMillis()
        fun ev(action: Int, v: View) = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, v.left + v.width / 2f, v.top + v.height / 2f, 0)

        grid.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, first))
        first.performLongClick() // 길게 누르기
        grid.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, target))
        grid.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, target))
        ShadowLooper.idleMainLooper()

        val selected = cls.getDeclaredField("selected").apply { isAccessible = true }.get(a) as Set<Long>
        assertEquals((1000L..1006L).toSet(), selected) // 0~6번, 7장
        assertTrue(cls.getDeclaredField("selectionMode").apply { isAccessible = true }.getBoolean(a))
        assertEquals("7장 선택", a.findViewById<TextView>(R.id.selTitle).text.toString())

        // 손가락을 2번으로 되돌리면 0~2번만
        val back = grid.findViewHolderForAdapterPosition(1 + 2)!!.itemView
        grid.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, back))
        assertEquals((1000L..1002L).toSet(), selected)

        // 손 떼고 나면 일반 스크롤로 돌아옴
        grid.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, back))
        assertTrue(!(cls.getDeclaredField("dragActive").apply { isAccessible = true }.getBoolean(a)))
        controller.pause().stop().destroy()
    }
}
