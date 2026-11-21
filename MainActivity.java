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
 *  - AndroidSilent: وضع الصامت أثناء الصلاة، بجدولة أصلية (منبّه دقيق + مصالحة) لا تعتمد على الصفحة.
 *  - AudioShade: إشعار «إيقاف» في الستارة يوقف القرآن/الأذكار دون فتح التطبيق.
 */
public class MainActivity extends BridgeActivity {
    private volatile boolean adhanPlaying = false;
    private boolean swallowVolume = false;
    private boolean statusBarVisible = false;
    private static MainActivity sInst;

    /** يُستدعى من زر «إيقاف» في الستارة: يأمر الصفحة بإيقاف كل صوت. */
    static void stopAudioFromShade() {
        final MainActivity a = sInst;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    a.getBridge().getWebView().evaluateJavascript(
                            "window.__zadStopAll&&window.__zadStopAll()", null);
                } catch (Throwable ignored) { }
            }
        });
    }

    private class AdhanBridge {
        @JavascriptInterface
        public void setPlaying(boolean on) {
            adhanPlaying = on;
        }
    }

    /** تشخيص: أسباب إنهاء العملية السابقة (ApplicationExitInfo) وآخر تنبيهات نقص الذاكرة. تقرؤها الصفحة في سجل الصوت. */
    private class DiagBridge {
        @JavascriptInterface
        public String info() {
            StringBuilder sb = new StringBuilder();
            try {
                android.content.SharedPreferences sp = getSharedPreferences("zad_diag", MODE_PRIVATE);
                sb.append("trim=").append(sp.getString("trim", "-")).append("\n");
                if (Build.VERSION.SDK_INT >= 30) {
                    android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    java.util.List<android.app.ApplicationExitInfo> l =
                            am.getHistoricalProcessExitReasons(getPackageName(), 0, 4);
                    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US);
                    for (android.app.ApplicationExitInfo e : l) {
                        sb.append("exit ").append(f.format(new java.util.Date(e.getTimestamp())))
                                .append(" reason=").append(e.getReason())
                                .append(" imp=").append(e.getImportance())
                                .append(" ").append(String.valueOf(e.getDescription())).append("\n");
                    }
                }
            } catch (Throwable t) {
                sb.append("diag-err ").append(t).append("\n");
            }
            sb.append("sdk=").append(Build.VERSION.SDK_INT).append(" ")
                    .append(Build.MANUFACTURER).append(" ").append(Build.MODEL);
            return sb.toString();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US);
            android.content.SharedPreferences sp = getSharedPreferences("zad_diag", MODE_PRIVATE);
            String old = sp.getString("trim", "");
            String[] parts = old.length() == 0 ? new String[0] : old.split(" \\| ");
            StringBuilder nb = new StringBuilder();
            for (int i = Math.max(0, parts.length - 5); i < parts.length; i++) nb.append(parts[i]).append(" | ");
            nb.append(f.format(new java.util.Date())).append(" lvl=").append(level);
            sp.edit().putString("trim", nb.toString()).apply();
        } catch (Throwable ignored) { }
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
        sInst = this;
        try {
            getBridge().getWebView().addJavascriptInterface(new AdhanBridge(), "AdhanNative");
            getBridge().getWebView().addJavascriptInterface(new OrientBridge(), "OrientNative");
            getBridge().getWebView().addJavascriptInterface(new AndroidSilent(this), "AndroidSilent");
            getBridge().getWebView().addJavascriptInterface(new AudioShade(this), "ZadShade");
            getBridge().getWebView().addJavascriptInterface(new DiagBridge(), "ZadDiag");
        } catch (Throwable ignored) { }
        // مصالحة الصامت عند كل تشغيل: تصلح أي حالة عالقة وتعيد ضبط المنبّه التالي
        try { AndroidSilent.reconcile(this); } catch (Throwable ignored) { }

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

    @Override
    public void onResume() {
        super.onResume();
        try { AndroidSilent.reconcile(this); } catch (Throwable ignored) { }
    }

    /** عند الخروج من التطبيق يبقى جافاسكربت الصفحة يعمل (إعادة محاولة الصوت، الانتقال للآية التالية) ولا يُجمَّد المؤقّت. */
    @Override
    public void onPause() {
        super.onPause();
        try { getBridge().getWebView().resumeTimers(); } catch (Throwable ignored) { }
    }

    @Override
    public void onStop() {
        super.onStop();
        try { getBridge().getWebView().resumeTimers(); } catch (Throwable ignored) { }
    }

    @Override
    public void onDestroy() {
        if (sInst == this) sInst = null;
        try { if (isFinishing()) AudioShade.cancel(getApplicationContext()); } catch (Throwable ignored) { }
        super.onDestroy();
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
