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

    /** اهتزاز أصلي للمسبحة والأذكار. يعيد حالة نصية: ok / noperm / haptic / err */
    private class VibBridge {
        private void haptic() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        View v = getBridge().getWebView();
                        int flags = android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING;
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS, flags);
                    } catch (Throwable ignored) { }
                }
            });
        }

        @JavascriptInterface
        public String vibrate(String spec) {
            boolean perm = true;
            try {
                perm = checkSelfPermission(android.Manifest.permission.VIBRATE)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) { }
            if (!perm) { haptic(); return "noperm"; }
            try {
                long[] pat;
                String s = spec == null ? "" : spec.trim();
                if (s.startsWith("[")) {
                    String[] p = s.substring(1, s.length() - 1).split(",");
                    pat = new long[p.length + 1];
                    for (int i = 0; i < p.length; i++) pat[i + 1] = Long.parseLong(p[i].trim());
                } else {
                    pat = new long[]{0, Math.max(1L, (long) Double.parseDouble(s))};
                }
                android.os.Vibrator v;
                if (Build.VERSION.SDK_INT >= 31) {
                    android.os.VibratorManager vm = (android.os.VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                    v = vm.getDefaultVibrator();
                } else {
                    v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                }
                if (v == null || !v.hasVibrator()) { haptic(); return "haptic"; }
                if (Build.VERSION.SDK_INT >= 26) {
                    android.os.VibrationEffect fx = android.os.VibrationEffect.createWaveform(pat, -1);
                    if (Build.VERSION.SDK_INT >= 33) {
                        v.vibrate(fx, new android.os.VibrationAttributes.Builder()
                                .setUsage(android.os.VibrationAttributes.USAGE_TOUCH).build());
                    } else {
                        v.vibrate(fx, new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                    }
                } else {
                    v.vibrate(pat, -1);
                }
                return "ok";
            } catch (Throwable t) {
                haptic();
                return "err";
            }
        }
    }

    /** أذونات الخلفية: استثناء البطارية + فتح شاشات التشغيل التلقائي/النوافذ المنبثقة (شاومي وغيرها). */
    private class PermBridge {
        private boolean go(android.content.Intent i) {
            try {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private boolean appSettings() {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()));
            return go(i);
        }

        @JavascriptInterface
        public String oem() {
            return (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + " " + (Build.BRAND == null ? "" : Build.BRAND);
        }

        @JavascriptInterface
        public boolean battery() {
            try {
                if (Build.VERSION.SDK_INT < 23) return true;
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                return pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Throwable t) {
                return true;
            }
        }

        @JavascriptInterface
        public boolean askBattery() {
            try {
                if (Build.VERSION.SDK_INT < 23) return true;
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName()));
                if (go(i)) return true;
                return go(new android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        || appSettings();
            } catch (Throwable t) {
                return appSettings();
            }
        }

        @JavascriptInterface
        public boolean overlay() {
            try {
                if (Build.VERSION.SDK_INT < 23) return true;
                return android.provider.Settings.canDrawOverlays(MainActivity.this);
            } catch (Throwable t) {
                return true;
            }
        }

        @JavascriptInterface
        public boolean askOverlay() {
            try {
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                if (go(i)) return true;
            } catch (Throwable ignored) { }
            return appSettings();
        }

        @JavascriptInterface
        public boolean exact() {
            try {
                if (Build.VERSION.SDK_INT < 31) return true;
                android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
                return am.canScheduleExactAlarms();
            } catch (Throwable t) {
                return true;
            }
        }

        @JavascriptInterface
        public boolean askExact() {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    android.content.Intent i = new android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + getPackageName()));
                    if (go(i)) return true;
                }
            } catch (Throwable ignored) { }
            return appSettings();
        }

        @JavascriptInterface
        public boolean openNotif() {
            try {
                android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                if (go(i)) return true;
            } catch (Throwable ignored) { }
            return appSettings();
        }

        @JavascriptInterface
        public boolean openAutostart() {
            String[][] c = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"}
            };
            for (String[] x : c) {
                android.content.Intent i = new android.content.Intent();
                i.setClassName(x[0], x[1]);
                if (go(i)) return true;
            }
            return appSettings();
        }

        @JavascriptInterface
        public boolean openPopup() {
            try {
                android.content.Intent i = new android.content.Intent("miui.intent.action.APP_PERM_EDITOR");
                i.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity");
                i.putExtra("extra_pkgname", getPackageName());
                if (go(i)) return true;
            } catch (Throwable ignored) { }
            return appSettings();
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
        sInst = this;
        try {
            getBridge().getWebView().addJavascriptInterface(new AdhanBridge(), "AdhanNative");
            getBridge().getWebView().addJavascriptInterface(new OrientBridge(), "OrientNative");
            getBridge().getWebView().addJavascriptInterface(new AndroidSilent(this), "AndroidSilent");
            getBridge().getWebView().addJavascriptInterface(new AudioShade(this), "ZadShade");
            getBridge().getWebView().addJavascriptInterface(new DiagBridge(), "ZadDiag");
            getBridge().getWebView().addJavascriptInterface(new VibBridge(), "ZadVib");
            getBridge().getWebView().addJavascriptInterface(new PermBridge(), "ZadPerm");
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
