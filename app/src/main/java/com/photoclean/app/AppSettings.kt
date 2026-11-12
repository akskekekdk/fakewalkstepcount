package com.photoclean.app

import android.content.Context

/** 앱 설정 (기기에 저장). */
class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 확대 유지 (기본 켜짐) */
    var keepZoom: Boolean
        get() = p.getBoolean("keep_zoom", true)
        set(v) = p.edit().putBoolean("keep_zoom", v).apply()

    /** 이어서 보기 (기본 켜짐) */
    var resume: Boolean
        get() = p.getBoolean("resume", true)
        set(v) = p.edit().putBoolean("resume", v).apply()

    var swipeUp: SwipeAction
        get() = enumOr(p.getString("swipe_up", null), SwipeAction.TRASH)
        set(v) = p.edit().putString("swipe_up", v.name).apply()

    var swipeDown: SwipeAction
        get() = enumOr(p.getString("swipe_down", null), SwipeAction.NONE)
        set(v) = p.edit().putString("swipe_down", v.name).apply()

    /** false: 왼쪽으로 밀면 다음 사진 (기본) / true: 왼쪽으로 밀면 이전 사진 */
    var swipeReverse: Boolean
        get() = p.getBoolean("swipe_reverse", false)
        set(v) = p.edit().putBoolean("swipe_reverse", v).apply()

    /** 흐린 사진 민감도 0=낮음 1=보통 2=높음 */
    var blurSensitivity: Int
        get() = p.getInt("blur_sensitivity", 1)
        set(v) = p.edit().putInt("blur_sensitivity", v).apply()

    var columns: Int
        get() = p.getInt("columns", 4)
        set(v) = p.edit().putInt("columns", v).apply()

    var lastPhotoId: Long
        get() = p.getLong("last_photo_id", -1)
        set(v) = p.edit().putLong("last_photo_id", v).apply()

    /** 앱 켤 때 새 버전 확인 (기본 켜짐) */
    var autoUpdate: Boolean
        get() = p.getBoolean("auto_update", true)
        set(v) = p.edit().putBoolean("auto_update", v).apply()

    var lastUpdateCheck: Long
        get() = p.getLong("last_update_check", 0)
        set(v) = p.edit().putLong("last_update_check", v).apply()

    private fun enumOr(name: String?, def: SwipeAction) =
        SwipeAction.entries.firstOrNull { it.name == name } ?: def
}
