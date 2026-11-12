package com.photoclean.app

import android.net.Uri

data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateMillis: Long,
    val size: Long,
    /** 폴더 (MediaStore 의 BUCKET) */
    val bucketId: String = "",
    val bucketName: String = "",
    /** 휴지통에 있는 사진만: 자동 영구 삭제 시각 (ms) */
    val expiresMillis: Long = 0,
    /** 즐겨찾기(하트) — 안드로이드 표준 즐겨찾기 값 */
    val favorite: Boolean = false,
)

data class Folder(val bucketId: String, val name: String, val count: Int, val cover: Photo)

enum class ViewMode { ALL, FAVORITES, BLURRY }

/** 사진 크게 보기에서 위/아래로 밀었을 때 할 일 */
enum class SwipeAction(val label: String, val short: String) {
    TRASH("휴지통으로 보내기", "휴지통"),
    FAVORITE("즐겨찾기 켜기/끄기", "즐겨찾기"),
    GRID("전체 보기로 돌아가기", "전체 보기"),
    NONE("사용 안 함", ""),
}

/** 전체 보기 목록의 한 줄: 월 구분 머리말 또는 사진 한 칸 */
sealed class GridRow {
    data class Header(val label: String, val count: Int) : GridRow()
    data class Item(val photoIndex: Int) : GridRow()
}
