package com.photoclean.app

import android.Manifest
import android.view.View
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** 앱을 켰을 때 바로 꺼지지 않고, 화면 전환이 되는지 확인. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {

    @Test
    fun launchesWithoutPermission() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertFalse(activity.isFinishing)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.gridScreen).visibility)
    }

    @Test
    fun screensAndSettingsWork() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        ShadowLooper.idleMainLooper()
        fun v(id: Int) = a.findViewById<View>(id)
        fun title() = a.findViewById<TextView>(R.id.gridTitle).text.toString()

        // 선택 모드 켜고 끄기
        v(R.id.btnSelect).performClick()
        assertEquals(View.VISIBLE, v(R.id.selBar).visibility)
        assertEquals(View.VISIBLE, v(R.id.headerSelect).visibility)
        v(R.id.btnSelCancel).performClick()
        assertEquals(View.GONE, v(R.id.selBar).visibility)

        // 보기 칩: 즐겨찾기 / 흐린 사진 / 전체
        v(R.id.chipFav).performClick(); ShadowLooper.idleMainLooper()
        assertEquals("즐겨찾기", title())
        v(R.id.chipBlur).performClick(); ShadowLooper.idleMainLooper()
        assertEquals("흐린 사진", title())
        v(R.id.chipAll).performClick(); ShadowLooper.idleMainLooper()
        assertEquals("사진 정리", title())

        // 폴더 시트 열기
        v(R.id.chipFolder).performClick(); ShadowLooper.idleMainLooper()

        // 설정 화면: 확대 유지 기본 켜짐 → 끄면 저장
        v(R.id.btnSettings).performClick()
        assertEquals(View.VISIBLE, v(R.id.settingsScreen).visibility)
        val sw = a.findViewById<MaterialSwitch>(R.id.swKeepZoom)
        assertTrue(sw.isChecked)
        v(R.id.rowKeepZoom).performClick()
        assertFalse(a.getSharedPreferences("settings", 0).getBoolean("keep_zoom", true))
        assertEquals("휴지통", a.findViewById<TextView>(R.id.tvSwipeUp).text.toString())
        a.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, v(R.id.gridScreen).visibility)

        // 휴지통 화면 열고 닫기
        v(R.id.btnOpenTrash).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, v(R.id.trashScreen).visibility)
        v(R.id.btnTrashBack).performClick()
        assertEquals(View.VISIBLE, v(R.id.gridScreen).visibility)

        controller.pause().stop().destroy()
    }

    @Test
    fun swipeSettingsPersist() {
        val s = AppSettings(RuntimeEnvironment.getApplication())
        assertEquals(SwipeAction.TRASH, s.swipeUp)
        assertEquals(SwipeAction.NONE, s.swipeDown)
        s.swipeUp = SwipeAction.FAVORITE
        s.swipeDown = SwipeAction.GRID
        s.swipeReverse = true
        val again = AppSettings(RuntimeEnvironment.getApplication())
        assertEquals(SwipeAction.FAVORITE, again.swipeUp)
        assertEquals(SwipeAction.GRID, again.swipeDown)
        assertTrue(again.swipeReverse)
    }
}
