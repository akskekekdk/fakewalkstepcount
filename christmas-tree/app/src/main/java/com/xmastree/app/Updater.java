package com.xmastree.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub 릴리즈에서 'xmas-v*' 태그의 새 버전을 확인하고, 내려받아 설치한다.
 * (PhotoClean 의 v2.1.* 릴리즈와 섞이지 않도록 xmas- 접두사만 본다.)
 */
public final class Updater {

    public static final String OWNER = "akskekekdk";
    public static final String REPO = "fakewalkstepcount";
    public static final String TAG_PREFIX = "xmas-v";
    public static final String ACTION_INSTALL_STATUS = "com.xmastree.app.INSTALL_STATUS";

    public static final class Release {
        public final String version, title, notes, apkUrl, pageUrl;
        public final long apkSize;
        Release(String v, String t, String n, String u, long s, String p) {
            version = v; title = t; notes = n; apkUrl = u; apkSize = s; pageUrl = p;
        }
    }

    public interface ProgressCb { void on(long got, long total); }

    /** "xmas-v1.3" / "1.3" -> [1,3] */
    public static List<Integer> versionParts(String v) {
        List<Integer> out = new ArrayList<>();
        v = v.trim();
        if (v.startsWith(TAG_PREFIX)) v = v.substring(TAG_PREFIX.length());
        else if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        for (String p : v.split("[._-]")) {
            try { out.add(Integer.parseInt(p.trim())); } catch (Exception ignore) {}
        }
        return out;
    }

    /** a 가 b 보다 새 버전이면 양수 */
    public static int compareVersions(String a, String b) {
        List<Integer> x = versionParts(a), y = versionParts(b);
        int n = Math.max(x.size(), y.size());
        for (int i = 0; i < n; i++) {
            int d = (i < x.size() ? x.get(i) : 0) - (i < y.size() ? y.get(i) : 0);
            if (d != 0) return d;
        }
        return 0;
    }

    /** xmas-v* 중 가장 높은 버전의 릴리즈(apk 포함)를 돌려준다. 없으면 null. */
    public static Release fetchLatest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=30").openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "ChristmasTree-app");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new RuntimeException("서버 응답 " + code);
            JSONArray arr = new JSONArray(readAll(c.getInputStream()));
            Release best = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String tag = o.optString("tag_name");
                if (!tag.startsWith(TAG_PREFIX)) continue;
                JSONArray assets = o.optJSONArray("assets");
                if (assets == null) continue;
                String apk = null; long size = 0;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.getJSONObject(j);
                    if (a.optString("name").toLowerCase().endsWith(".apk")) {
                        apk = a.getString("browser_download_url");
                        size = a.optLong("size");
                        break;
                    }
                }
                if (apk == null) continue;
                Release r = new Release(
                        tag.substring(TAG_PREFIX.length()),
                        o.optString("name", tag),
                        o.optString("body", ""),
                        apk, size, o.optString("html_url"));
                if (best == null || compareVersions(r.version, best.version) > 0) best = r;
            }
            return best;
        } finally {
            c.disconnect();
        }
    }

    /** apk 내려받기 */
    public static File download(Context ctx, Release r, ProgressCb cb) throws Exception {
        File dir = new File(ctx.getCacheDir(), "updates");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File out = new File(dir, "ChristmasTree-" + r.version + ".apk");
        HttpURLConnection c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ChristmasTree-app");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new RuntimeException("다운로드 실패 (" + code + ")");
            long total = c.getContentLengthLong();
            if (total <= 0) total = r.apkSize;
            long got = 0;
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    os.write(buf, 0, n);
                    got += n;
                    if (cb != null) cb.on(got, total);
                }
            } finally {
                os.close(); in.close();
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** 시스템 설치 세션으로 넘긴다. */
    public static void install(Context ctx, File apk) throws Exception {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        PackageInstaller.Session s = installer.openSession(id);
        OutputStream out = s.openWrite("xmas.apk", 0, apk.length());
        InputStream in = new FileInputStream(apk);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            s.fsync(out);
        } finally {
            out.close(); in.close();
        }
        Intent intent = new Intent(ctx, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, id, intent, flags);
        s.commit(pi.getIntentSender());
        s.close();
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private Updater() {}
}
