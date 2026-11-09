package com.zadalmuslim.app;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;

/**
 * - إيقاف الأذان بزر الصوت أو بسحب ستارة الإشعارات
 * - تدوير الشاشة داخل المصحف
 * - جسر إشعار الوسائط (MediaNative): تشغيل/إيقاف/السابق/التالي في الستارة وشاشة القفل
 * - جسر النظام (SysNative): فتح إعدادات البطارية وإعدادات التطبيق
 */
public class MainActivity extends BridgeActivity {

    private static MainActivity inst;

    private volatile boolean adhanPlaying = false;
    private boolean swallowVolume = false;
    private boolean statusBarVisible = true;

    /** تستدعيه صفحة التطبيق عند بدء الأذان وانتهائه */
    private class AdhanBridge {
        @JavascriptInterface
        public void setPlaying(boolean on) {
            adhanPlaying = on;
        }
    }

    /** تدوير الشاشة داخل المصحف حتى لو كان «التدوير التلقائي» مغلقًا في الهاتف */
    private class OrientBridge {
        @JavascriptInterface
        public void setSensor(boolean on) {
            runOnUiThread(() -> setRequestedOrientation(on
                    ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        }
    }

    /** إشعار الوسائط: الصفحة تُخبر الخدمة الأصلية بما يُشغَّل وحالته */
    private class MediaBridge {
        @JavascriptInterface
        public void meta(String title, String artist, boolean hasPrev, boolean hasNext) {
            MediaPlaybackService.setMeta(getApplicationContext(), title, artist, hasPrev, hasNext);
        }

        @JavascriptInterface
        public void state(boolean playing, long posMs, long durMs) {
            MediaPlaybackService.setState(getApplicationContext(), playing, posMs, durMs);
        }

        @JavascriptInterface
        public void clear() {
            MediaPlaybackService.clear(getApplicationContext());
        }
    }

    /** اختصارات إعدادات النظام لضمان وصول التنبيهات */
    private class SysBridge {
        @JavascriptInterface
        public void openBattery() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 23) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } else {
                        openAppDetails();
                    }
                } catch (Exception e) {
                    openAppDetails();
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> openAppDetails());
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) { }
    }

    /** تستدعيها خدمة الوسائط لإيصال أزرار الستارة إلى الصفحة */
    static void sendToJs(final String cmd, final long val) {
        final MainActivity a = inst;
        if (a == null) return;
        a.runOnUiThread(() -> {
            try {
                a.getBridge().getWebView().evaluateJavascript(
                        "window.zadMediaCmd&&window.zadMediaCmd('" + cmd + "'," + val + ")", null);
            } catch (Exception ignored) { }
        });
    }

    private void stopAdhan() {
        if (!adhanPlaying) return;
        adhanPlaying = false;
        runOnUiThread(() -> {
            try {
                getBridge().getWebView().evaluateJavascript(
                        "window.adStopNative&&window.adStopNative()", null);
            } catch (Exception ignored) { }
        });
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inst = this;
        try {
            WebView wv = getBridge().getWebView();
            wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
            wv.addJavascriptInterface(new AdhanBridge(), "AdhanNative");
            wv.addJavascriptInterface(new OrientBridge(), "OrientNative");
            wv.addJavascriptInterface(new MediaBridge(), "MediaNative");
            wv.addJavascriptInterface(new SysBridge(), "SysNative");
        } catch (Exception ignored) { }

        // ظهور شريط الحالة (السحبة الأولى في وضع ملء الشاشة) = إيقاف الأذان
        final View decor = getWindow().getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) -> {
            boolean vis = insets.isVisible(WindowInsetsCompat.Type.statusBars());
            if (vis && !statusBarVisible) stopAdhan();
            statusBarVisible = vis;
            return ViewCompat.onApplyWindowInsets(v, insets);
        });
        if (Build.VERSION.SDK_INT < 30) {
            decor.setOnSystemUiVisibilityChangeListener(flags -> {
                if ((flags & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) stopAdhan();
            });
        }
    }

    // إبقاء الصفحة تعمل أثناء تشغيل التلاوة والشاشة مطفأة أو التطبيق في الخلفية
    @Override
    public void onPause() {
        super.onPause();
        if (MediaPlaybackService.playing) {
            try {
                WebView wv = getBridge().getWebView();
                wv.onResume();
                wv.resumeTimers();
            } catch (Exception ignored) { }
        }
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        super.onDestroy();
    }

    // سحب الستارة (في الوضع العادي) يُفقد النافذة التركيز
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) stopAdhan();
    }

    // أزرار الصوت
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && adhanPlaying) {
                swallowVolume = true;
                stopAdhan();
                return true;
            }
            if (swallowVolume) {
                if (event.getAction() == KeyEvent.ACTION_UP) swallowVolume = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
