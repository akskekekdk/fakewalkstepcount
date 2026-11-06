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
