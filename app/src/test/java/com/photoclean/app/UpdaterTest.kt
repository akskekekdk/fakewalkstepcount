package com.photoclean.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterTest {

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updater.compareVersions("v2.1.12", "2.1.9") > 0)
        assertTrue(Updater.compareVersions("2.1.9", "2.1.12") < 0)
        assertEquals(0, Updater.compareVersions("v2.1.0", "2.1"))
        assertTrue(Updater.compareVersions("2.2.0", "2.1.99") > 0)
        assertTrue(Updater.compareVersions("2.1.1", "2.0") > 0)
    }

    @Test
    fun parsesGithubReleaseJson() {
        val json = """
            {"tag_name":"v2.1.14","name":"사진 정리 2.1.14","body":"- 새 기능\n- 버그 수정",
             "html_url":"https://github.com/a/b/releases/tag/v2.1.14",
             "assets":[
               {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
               {"name":"PhotoClean-2.1.14.apk","browser_download_url":"https://x/PhotoClean-2.1.14.apk","size":4544068}
             ]}
        """.trimIndent()
        val r = Updater.parseRelease(json)!!
        assertEquals("2.1.14", r.version)
        assertEquals("https://x/PhotoClean-2.1.14.apk", r.apkUrl)
        assertEquals(4544068L, r.apkSize)
        assertTrue(r.notes.contains("버그 수정"))
        assertNull(Updater.parseRelease("""{"tag_name":"v1","assets":[]}"""))
    }

    @Test
    fun buildVersionFollowsBuildNumber() {
        // 버전 = 2.1.<빌드 번호>, versionCode = 100 + 빌드 번호 → 다음 빌드가 항상 더 새 버전
        val parts = Updater.versionParts(BuildConfig.VERSION_NAME)
        assertEquals(listOf(2, 1), parts.take(2))
        assertEquals(100 + parts[2], BuildConfig.VERSION_CODE)
        val next = "2.1.${parts[2] + 1}"
        assertTrue(Updater.compareVersions(next, BuildConfig.VERSION_NAME) > 0)
    }
}
