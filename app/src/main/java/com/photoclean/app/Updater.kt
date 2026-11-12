package com.photoclean.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub 릴리즈에서 새 버전을 확인하고, 내려받아 설치한다. */
object Updater {

    const val OWNER = "akskekekdk"
    const val REPO = "fakewalkstepcount"
    const val ACTION_INSTALL_STATUS = "com.photoclean.app.INSTALL_STATUS"

    data class Release(
        val version: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String,
    )

    /** "v2.1.12" / "2.1.12" → [2,1,12] */
    fun versionParts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split('.', '-', '_').mapNotNull { it.toIntOrNull() }

    /** a 가 b 보다 새 버전이면 양수 */
    fun compareVersions(a: String, b: String): Int {
        val x = versionParts(a)
        val y = versionParts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** GitHub "latest release" 응답 해석. APK 가 없으면 null */
    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                val tag = o.optString("tag_name")
                return Release(
                    version = tag.removePrefix("v"),
                    title = o.optString("name").ifEmpty { tag },
                    notes = o.optString("body"),
                    apkUrl = a.getString("browser_download_url"),
                    apkSize = a.optLong("size"),
                    pageUrl = o.optString("html_url"),
                )
            }
        }
        return null
    }

    /** 최신 릴리즈 가져오기 (네트워크, 백그라운드에서 호출) */
    fun fetchLatest(): Release? {
        val c = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "PhotoClean-app")
        try {
            if (c.responseCode == 404) return null // 아직 릴리즈 없음
            if (c.responseCode != 200) throw RuntimeException("서버 응답 ${c.responseCode}")
            return parseRelease(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** APK 내려받기. onProgress(받은 바이트, 전체 바이트) */
    fun download(context: Context, r: Release, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "PhotoClean-${r.version}.apk")
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "PhotoClean-app")
        try {
            if (c.responseCode != 200) throw RuntimeException("다운로드 실패 (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            var got = 0L
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) { out.delete(); return null }
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        got += n
                        onProgress(got, total)
                    }
                }
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    /**
     * 시스템 설치 화면으로 넘긴다. 결과는 MainActivity 의 onNewIntent 로
     * [ACTION_INSTALL_STATUS] 가 돌아온다 (확인이 필요하면 확인 화면을 띄움).
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                // 이 앱이 직접 설치한 적이 있으면 확인 없이 업데이트될 수 있음 (시스템이 결정)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("PhotoClean.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val intent = Intent(context, MainActivity::class.java).setAction(ACTION_INSTALL_STATUS)
            val pi = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pi.intentSender)
        }
    }
}
