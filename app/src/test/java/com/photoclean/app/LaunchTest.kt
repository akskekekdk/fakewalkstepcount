package com.photoclean.app

import android.Manifest
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 앱을 켰을 때 바로 꺼지지 않는지 확인. */
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
    fun launchesWithPermissionAndOpensSelection() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(activity.isFinishing)
        // 선택 모드 켜고 끄기, 뒤로가기
        activity.findViewById<View>(R.id.btnSelect).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.selBar).visibility)
        activity.findViewById<View>(R.id.btnSelCancel).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.selBar).visibility)
        // 즐겨찾기 탭 ↔ 전체 탭
        activity.findViewById<View>(R.id.tabFav).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("♥ 즐겨찾기", activity.findViewById<android.widget.TextView>(R.id.gridTitle).text.toString())
        activity.findViewById<View>(R.id.tabAll).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("사진 정리", activity.findViewById<android.widget.TextView>(R.id.gridTitle).text.toString())
        // 설정: 확대 유지 기본값 켜짐, 끄면 저장됨
        activity.findViewById<View>(R.id.btnSettings).performClick()
        val dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        org.junit.Assert.assertTrue(dlg.isShowing)
        // Robolectric 은 목록을 그리지 않아 체크 상태가 화면에 반영되지 않으므로, 실제 화면처럼 '켜짐'으로 맞춘 뒤 탭
        dlg.listView.setItemChecked(0, true)
        dlg.listView.performItemClick(dlg.listView, 0, 0)
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(activity.getSharedPreferences("settings", 0).getBoolean("keep_zoom", true))
        // 휴지통 화면 열고 닫기
        activity.findViewById<View>(R.id.btnOpenTrash).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.trashScreen).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.gridScreen).visibility)
        activity.findViewById<View>(R.id.btnTrashBack).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.trashScreen).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.gridScreen).visibility)
        controller.pause().stop().destroy()
    }
}
