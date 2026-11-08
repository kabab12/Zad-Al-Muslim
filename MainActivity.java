package com.zadalmuslim.app;

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
        try {
            getBridge().getWebView().addJavascriptInterface(new AdhanBridge(), "AdhanNative");
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
}
