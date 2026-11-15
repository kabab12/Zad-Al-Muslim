package com.zadalmuslim.app;

import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;

/**
 * إعادة كتابة مطابقة لسلوك MainActivity الأصلي في التطبيق (استُخرج من الـ APK):
 *  - AdhanNative.setPlaying(): الصفحة تخبرنا أن الأذان يعمل.
 *  - أثناء الأذان: زر الصوت، أو سحب شريط الحالة، أو فقدان التركيز ← إيقاف الأذان (window.adStopNative).
 *  - OrientNative.setSensor(): تدوير القرآن. صار يتبع إعداد «التدوير التلقائي» في الهاتف.
 *  - AndroidSilent: وضع الصامت أثناء الصلاة (جديد).
 */
public class MainActivity extends BridgeActivity {
    private volatile boolean adhanPlaying = false;
    private boolean swallowVolume = false;
    private boolean statusBarVisible = false;

    private class AdhanBridge {
        @JavascriptInterface
        public void setPlaying(boolean on) {
            adhanPlaying = on;
        }
    }

    private class OrientBridge {
        @JavascriptInterface
        public void setSensor(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // في القارئ نترك للنظام القرار فيدور فقط إذا كان «التدوير التلقائي» مفعّلًا.
                        // (النسخة الأصلية كانت تستخدم FULL_SENSOR الذي يتجاهل قفل التدوير.)
                        setRequestedOrientation(on
                                ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    } catch (Throwable ignored) { }
                }
            });
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getBridge().getWebView().addJavascriptInterface(new AdhanBridge(), "AdhanNative");
            getBridge().getWebView().addJavascriptInterface(new OrientBridge(), "OrientNative");
            getBridge().getWebView().addJavascriptInterface(new AndroidSilent(this), "AndroidSilent");
        } catch (Throwable ignored) { }

        // ظهور شريط الحالة (سحب الستارة) يوقف الأذان
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            boolean vis = insets.isVisible(WindowInsetsCompat.Type.statusBars());
            if (vis && !statusBarVisible) stopAdhan();
            statusBarVisible = vis;
            return ViewCompat.onApplyWindowInsets(v, insets);
        });
        if (Build.VERSION.SDK_INT < 30) {
            getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) stopAdhan();
            });
        }
    }

    private void stopAdhan() {
        if (!adhanPlaying) return;
        adhanPlaying = false;
        runOnUiThread(() -> {
            try {
                getBridge().getWebView().evaluateJavascript("window.adStopNative&&window.adStopNative()", null);
            } catch (Throwable ignored) { }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) stopAdhan();
    }
}
