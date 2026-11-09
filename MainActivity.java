package com.zadalmuslim.app;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;

/**
 * إيقاف الأذان:
 *  1) بزر الصوت (رفع أو خفض)
 *  2) بسحب ستارة الإشعارات ولو قليلًا
 */
public class MainActivity extends BridgeActivity {

    /** المثيل الحالي: تستخدمه خدمة الوسائط لإرسال أزرار الستارة إلى الصفحة */
    static volatile MainActivity cur;

    static void sendToJs(String cmd, long v) {
        final MainActivity a = cur;
        if (a == null) return;
        a.runOnUiThread(() -> {
            try {
                a.getBridge().getWebView().evaluateJavascript(
                        "window.zadMediaCmd&&zadMediaCmd('" + cmd + "'," + v + ")", null);
            } catch (Exception ignored) { }
        });
    }

    /** جسر إشعار الوسائط: الصفحة تخبر الخدمة بالعنوان وحالة التشغيل والموضع */
    private class MediaBridge {
        @JavascriptInterface
        public void meta(String t, String a, boolean prev, boolean next) {
            MediaPlaybackService.setMeta(getApplicationContext(), t, a, prev, next);
        }
        @JavascriptInterface
        public void state(boolean playing, long pos, long dur) {
            MediaPlaybackService.setState(getApplicationContext(), playing, pos, dur);
        }
        @JavascriptInterface
        public void clear() { MediaPlaybackService.clear(getApplicationContext()); }
    }

    /** فتح إعدادات النظام المهمة لوصول التنبيهات */
    private class SysBridge {
        @JavascriptInterface
        public void openBattery() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                catch (Exception e) { openApp(); }
            });
        }
        @JavascriptInterface
        public void openAppSettings() { runOnUiThread(() -> openApp()); }
        private void openApp() {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
        }
    }

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
        cur = this;
        try {
            getBridge().getWebView().addJavascriptInterface(new MediaBridge(), "MediaNative");
            getBridge().getWebView().addJavascriptInterface(new SysBridge(), "SysNative");
        } catch (Exception ignored) { }
        try {
            getBridge().getWebView().addJavascriptInterface(new AdhanBridge(), "AdhanNative");
            getBridge().getWebView().addJavascriptInterface(new OrientBridge(), "OrientNative");
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

    // إبقاء الصفحة تعمل (صوتًا ومؤقتات) حين يكون التشغيل جاريًا والتطبيق في الخلفية
    @Override
    public void onPause() {
        super.onPause();
        keepWebAlive();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepWebAlive();
    }

    private void keepWebAlive() {
        if (!MediaPlaybackService.playing && !adhanPlaying) return;
        try {
            getBridge().getWebView().onResume();
            getBridge().getWebView().resumeTimers();
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        if (cur == this) cur = null;
        MediaPlaybackService.clear(getApplicationContext());
        super.onDestroy();
    }
}
