package com.photoclean.app

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoLogicTest {

    private fun ms(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    private fun photo(id: Long, date: Long, bucket: String = "b1", name: String = "Camera", fav: Boolean = false) =
        Photo(id, Uri.parse("content://x/$id"), "p$id.jpg", date, 100, bucket, name, favorite = fav)

    @Test
    fun rowsHaveOneHeaderPerMonth() {
        val photos = listOf(
            photo(1, ms(2026, 10, 30)), photo(2, ms(2026, 10, 2)),
            photo(3, ms(2026, 9, 15)),
            photo(4, ms(2025, 12, 31)), photo(5, ms(2025, 12, 1)),
        )
        val rows = PhotoLogic.buildRows(photos, withHeaders = true)
        val headers = rows.filterIsInstance<GridRow.Header>()
        assertEquals(listOf("2026년 10월", "2026년 9월", "2025년 12월"), headers.map { it.label })
        assertEquals(listOf(2, 1, 2), headers.map { it.count })
        assertEquals(photos.size + 3, rows.size)
        // 머리말 없이
        assertEquals(photos.size, PhotoLogic.buildRows(photos, withHeaders = false).size)
    }

    @Test
    fun blurryImageScoresLowerThanSharp() {
        val w = 64
        val h = 64
        val rnd = Random(1)
        // 선명: 무작위 바둑판 같은 강한 윤곽
        val sharp = IntArray(w * h) { if (((it % w) / 4 + (it / w) / 4) % 2 == 0) 230 else 20 }
        // 흐림: 부드러운 그라데이션 + 약간의 잡음
        val blurry = IntArray(w * h) { ((it % w) * 2 + (it / w)).coerceIn(0, 255) + rnd.nextInt(2) }
        val s = PhotoLogic.laplacianVariance(sharp, w, h)
        val b = PhotoLogic.laplacianVariance(blurry, w, h)
        assertTrue("sharp=$s blurry=$b", s > PhotoLogic.blurThreshold(2))
        assertTrue("blurry=$b", b < PhotoLogic.blurThreshold(0))
    }

    @Test
    fun filterByFolderFavoriteAndBlur() {
        val all = listOf(
            photo(1, 5000, "cam", "Camera", fav = true),
            photo(2, 4000, "shot", "Screenshots"),
            photo(3, 3000, "cam", "Camera"),
            photo(4, 2000, "cam", "Camera"),
        )
        assertEquals(listOf(1L, 3L, 4L), PhotoLogic.applyFilter(all, ViewMode.ALL, "cam", emptyMap(), 80.0).map { it.id })
        assertEquals(listOf(1L), PhotoLogic.applyFilter(all, ViewMode.FAVORITES, null, emptyMap(), 80.0).map { it.id })
        val scores = mapOf(1L to 500f, 2L to 10f, 3L to 70f, 4L to 30f)
        // 흐린 사진: 기준(80) 미만만, 가장 흐린 것부터
        assertEquals(listOf(2L, 4L, 3L), PhotoLogic.applyFilter(all, ViewMode.BLURRY, null, scores, 80.0).map { it.id })
        assertEquals(listOf(4L, 3L), PhotoLogic.applyFilter(all, ViewMode.BLURRY, "cam", scores, 80.0).map { it.id })
    }

    @Test
    fun insertSortedKeepsNewestFirst() {
        val list = mutableListOf(photo(1, 3000), photo(2, 2000), photo(3, 1000))
        PhotoLogic.insertSorted(list, photo(9, 2500))
        PhotoLogic.insertSorted(list, photo(8, 500))
        PhotoLogic.insertSorted(list, photo(7, 9000))
        assertEquals(listOf(7L, 1L, 9L, 2L, 3L, 8L), list.map { it.id })
    }

    @Test
    fun foldersGroupedAndNamed() {
        val all = listOf(
            photo(1, 3, "cam", "Camera"), photo(2, 2, "kt", "KakaoTalk"),
            photo(3, 1, "cam", "Camera"), photo(4, 0, "s", "Screenshots"),
        )
        val f = PhotoLogic.groupFolders(all)
        assertEquals("카메라", f[0].name)
        assertEquals(2, f[0].count)
        assertEquals(1L, f[0].cover.id)
        assertTrue(f.any { it.name == "카카오톡" } && f.any { it.name == "스크린샷" })
    }
}
