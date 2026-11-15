package com.xmastree.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 글자로 피어나는 크리스마스 트리.
 * assets/index.html(순수 HTML/JS 애니메이션)을 WebView 전체화면으로 띄우고,
 * GitHub 릴리즈(xmas-v*) 기반의 '앱 내 업데이트' 기능을 제공한다.
 */
public class MainActivity extends Activity {

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean updating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(0xFF070B18);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        hideSystemUi();

        // 실행할 때마다 조용히 새 버전 확인 (있으면 안내)
        ui.postDelayed(() -> checkForUpdate(false), 2500);
    }

    // ---------- 웹 → 앱 다리 ----------
    private final class Bridge {
        @JavascriptInterface
        public String appVersion() {
            return currentVersion();
        }

        @JavascriptInterface
        public void checkUpdate() {
            ui.post(() -> {
                Toast.makeText(MainActivity.this, "업데이트 확인 중…", Toast.LENGTH_SHORT).show();
                checkForUpdate(true);
            });
        }
    }

    private String currentVersion() {
        try {
            PackageManager pm = getPackageManager();
            return pm.getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    // ---------- 업데이트 ----------
    private void checkForUpdate(final boolean manual) {
        if (updating) return;
        new Thread(() -> {
            try {
                final Updater.Release r = Updater.fetchLatest();
                final String cur = currentVersion();
                ui.post(() -> {
                    if (r != null && Updater.compareVersions(r.version, cur) > 0) {
                        promptUpdate(r, cur);
                    } else if (manual) {
                        Toast.makeText(this, "최신 버전이에요 (v" + cur + ")", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                if (manual) ui.post(() ->
                        Toast.makeText(this, "업데이트 확인 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void promptUpdate(final Updater.Release r, String cur) {
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 240) notes = notes.substring(0, 240) + "…";
        String msg = "현재 v" + cur + " → 새 버전 v" + r.version + "\n\n" + notes;
        new AlertDialog.Builder(this)
                .setTitle("🎄 새 버전이 있어요")
                .setMessage(msg)
                .setNegativeButton("나중에", null)
                .setPositiveButton("지금 업데이트", (d, w) -> startDownload(r))
                .show();
    }

    private void startDownload(final Updater.Release r) {
        if (updating) return;
        updating = true;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        final TextView tv = new TextView(this);
        tv.setText("다운로드 중… 0%");
        tv.setGravity(Gravity.CENTER);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(tv);
        box.addView(bar, lp);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("크리스마스 트리 v" + r.version)
                .setView(box)
                .setCancelable(false)
                .create();
        dlg.show();

        new Thread(() -> {
            try {
                File apk = Updater.download(this, r, (got, total) -> {
                    final int pct = total > 0 ? (int) (got * 100 / total) : 0;
                    ui.post(() -> {
                        bar.setProgress(pct);
                        tv.setText("다운로드 중… " + pct + "%");
                    });
                });
                ui.post(() -> {
                    tv.setText("설치를 준비하고 있어요…");
                    bar.setProgress(100);
                });
                Updater.install(this, apk);
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    updating = false;
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    updating = false;
                    Toast.makeText(this, "업데이트 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            int status = intent.getIntExtra(
                    android.content.pm.PackageInstaller.EXTRA_STATUS,
                    android.content.pm.PackageInstaller.STATUS_FAILURE);
            if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    try { startActivity(confirm); } catch (Exception ignore) {}
                }
            } else if (status == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED) {
                // 사용자가 취소 — 조용히 무시
            } else if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                String m = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(this, "설치 실패: " + (m == null ? status : m), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void hideSystemUi() {
        Window w = getWindow();
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
