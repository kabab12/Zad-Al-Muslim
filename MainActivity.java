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
    /** مهلة أمان: لا يُوقَف الأذان تلقائيًا (فقدان تركيز/شريط الحالة) في أول ثوانٍ لأن فتح اللوحة نفسه يسبب هذه الأحداث */
    private volatile long adhanGraceUntil = 0;
    private volatile long screenEventAt = 0;
    private android.content.BroadcastReceiver screenRx;
    private boolean swallowVolume = false;
    private boolean statusBarVisible = false;
    private static MainActivity sInst;
    /** هل شاشة التطبيق ظاهرة الآن (لا حاجة لإشعار اللوحة حينها) */
    static volatile boolean sVisible = false;
    /** وقت آخر أذان (يُضبط من AdhanScreenReceiver) */
    static volatile long sAdhanAt = 0;
    private boolean adhanOpened = false;
    /** غطاء أصلي فوري يظهر لحظة الأذان حتى تُحمَّل صفحة الويب (التحميل البارد يستغرق ثوانٍ) */
    private android.view.View adhanCover = null;
    private static final String[] ADHAN_NAMES = {"الفجر", "", "الظهر", "العصر", "المغرب", "العشاء"};

    /** التطبيق ظاهر: نطلب من الصفحة عرض لوحة الأذان في لحظتها بلا انتظار مؤقّت الصفحة. */
    static void fireAdhanJs(final int idx) {
        final MainActivity a = sInst;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    a.getBridge().getWebView().evaluateJavascript(
                            "window.adScrNative&&window.adScrNative(" + idx + ")", null);
                } catch (Throwable ignored) { }
            }
        });
    }

    /** يُستدعى من زر «إيقاف» في الستارة: يأمر الصفحة بإيقاف كل صوت. */
    /** ينفّذ جافاسكربت في الصفحة (من خدمة التنزيل وزر الإيقاف). */
    static void evalJs(final String js) {
        final MainActivity a = sInst;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try { a.getBridge().getWebView().evaluateJavascript(js, null); } catch (Throwable ignored) { }
            }
        });
    }

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
            if (on) adhanGraceUntil = System.currentTimeMillis() + 7000;
            adhanPlaying = on;
        }

        /** انتهى الأذان أو أُوقف صوته: تبقى اللوحة ظاهرة حتى يغلقها المستخدم، لكن لا نُبقي الشاشة مضاءة بلا نهاية. */
        @JavascriptInterface
        public void screenEnded() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try { getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
                }
            });
        }

        /** ظهرت لوحة الصفحة: نزيل الغطاء الأصلي المؤقّت. */
        @JavascriptInterface
        public void screenShown() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    removeAdhanCover();
                }
            });
        }

        /** أُغلقت لوحة الأذان: إن كانت فوق القفل نرجع إلى شاشة القفل. */
        @JavascriptInterface
        public void screenClosed() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    endAdhanScreen();
                }
            });
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
            try { getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false); } catch (Throwable ignored) { }
            getBridge().getWebView().addJavascriptInterface(new AdhanBridge(), "AdhanNative");
            getBridge().getWebView().addJavascriptInterface(new OrientBridge(), "OrientNative");
            getBridge().getWebView().addJavascriptInterface(new AndroidSilent(this), "AndroidSilent");
            getBridge().getWebView().addJavascriptInterface(new AudioShade(this), "ZadShade");
            getBridge().getWebView().addJavascriptInterface(new DiagBridge(), "ZadDiag");
            getBridge().getWebView().addJavascriptInterface(new VibBridge(), "ZadVib");
            getBridge().getWebView().addJavascriptInterface(new PermBridge(), "ZadPerm");
            getBridge().getWebView().addJavascriptInterface(new ZadFiles(this), "ZadFiles");
            getBridge().getWebView().addJavascriptInterface(new AdhanScreen(this), "AdhanScreen");
            getBridge().getWebView().addJavascriptInterface(new ZadDl(this), "ZadDl");
        } catch (Throwable ignored) { }
        // مصالحة الصامت عند كل تشغيل: تصلح أي حالة عالقة وتعيد ضبط المنبّه التالي
        try { AndroidSilent.reconcile(this); } catch (Throwable ignored) { }
        try { AdhanScreen.schedule(this); } catch (Throwable ignored) { }
        handleAdhanIntent(getIntent());
        // زر الطاقة (إطفاء/تشغيل الشاشة) لا يوقف شيئًا: نتجاهل أحداث الشاشة والتركيز المصاحبة له
        try {
            screenRx = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    screenEventAt = System.currentTimeMillis();
                }
            };
            android.content.IntentFilter sf = new android.content.IntentFilter();
            sf.addAction(android.content.Intent.ACTION_SCREEN_ON);
            sf.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            sf.addAction(android.content.Intent.ACTION_USER_PRESENT);
            registerReceiver(screenRx, sf);
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

    /** لوحة الأذان: تظهر فوق قفل الشاشة وتشغّلها، ثم تُعرض اللوحة في الصفحة. */
    private void handleAdhanIntent(android.content.Intent it) {
        try {
            if (it == null || !it.hasExtra(AdhanScreen.EXTRA_IDX)) return;
            final int idx = it.getIntExtra(AdhanScreen.EXTRA_IDX, -1);
            final long fms = it.getLongExtra("ms", 0);
            it.removeExtra(AdhanScreen.EXTRA_IDX);
            it.removeExtra("ms");
            if (idx < 0) return;
            // لا تُعرض اللوحة إلا في وقت الأذان نفسه؛ نية قديمة (إعادة فتح من التطبيقات الأخيرة…) تُتجاهل
            long dlt = System.currentTimeMillis() - fms;
            if (fms <= 0 || dlt < -10000 || dlt > 50000L) return;
            adhanOpened = true;
            adhanGraceUntil = System.currentTimeMillis() + 10000;
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            try {
                ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(AdhanScreen.NID);
            } catch (Throwable ignored) { }
            showAdhanCover(idx);
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            final String js = "window.adScrNative&&window.adScrNative(" + idx + ")";
            // محاولات متقاربة: الصفحة قد تحتاج ثوانٍ للتحميل البارد، فنعرض اللوحة فور جاهزيتها
            long[] delays = {0, 250, 600, 1000, 1500, 2200, 3000, 4000, 5500, 7500, 10000, 14000, 20000};
            for (long d : delays) {
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try { getBridge().getWebView().evaluateJavascript(js, null); } catch (Throwable ignored) { }
                    }
                }, d);
            }
        } catch (Throwable ignored) { }
    }

    /** غطاء بسيط فوري (اسم الصلاة) فوق الـWebView لحين ظهور اللوحة المتحركة. */
    private void showAdhanCover(int idx) {
        try {
            removeAdhanCover();
            String nm = idx >= 0 && idx < ADHAN_NAMES.length ? ADHAN_NAMES[idx] : "";
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setGravity(android.view.Gravity.CENTER);
            box.setBackgroundColor(0xFF050806);
            box.setClickable(true);
            android.widget.TextView t1 = new android.widget.TextView(this);
            t1.setText("أذان");
            t1.setTextColor(0xFFE7CF93);
            t1.setTextSize(20);
            t1.setGravity(android.view.Gravity.CENTER);
            android.widget.TextView t2 = new android.widget.TextView(this);
            t2.setText(nm);
            t2.setTextColor(0xFFF0CF7A);
            t2.setTextSize(58);
            t2.setGravity(android.view.Gravity.CENTER);
            android.widget.TextView t3 = new android.widget.TextView(this);
            t3.setText("حيّ على الصلاة");
            t3.setTextColor(0xB3F5EAD0);
            t3.setTextSize(18);
            t3.setGravity(android.view.Gravity.CENTER);
            box.addView(t1);
            box.addView(t2);
            box.addView(t3);
            addContentView(box, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            adhanCover = box;
            // أمان: لا يبقى الغطاء أكثر من 25 ثانية مهما حصل
            box.postDelayed(new Runnable() {
                @Override
                public void run() {
                    removeAdhanCover();
                }
            }, 25000);
        } catch (Throwable ignored) { }
    }

    private void removeAdhanCover() {
        try {
            final android.view.View v = adhanCover;
            adhanCover = null;
            if (v == null) return;
            v.animate().alpha(0f).setDuration(500).withEndAction(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.view.ViewParent pr = v.getParent();
                        if (pr instanceof android.view.ViewGroup) ((android.view.ViewGroup) pr).removeView(v);
                    } catch (Throwable ignored) { }
                }
            }).start();
        } catch (Throwable ignored) { }
    }

    private void endAdhanScreen() {
        removeAdhanCover();
        if (!adhanOpened) return;
        adhanOpened = false;
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            boolean locked = km != null && km.isKeyguardLocked();
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (locked) moveTaskToBack(true);
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(false);
                setTurnScreenOn(false);
            } else {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAdhanIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        sVisible = true;
        try { AndroidSilent.reconcile(this); } catch (Throwable ignored) { }
    }

    /** عند الخروج من التطبيق يبقى جافاسكربت الصفحة يعمل (إعادة محاولة الصوت، الانتقال للآية التالية) ولا يُجمَّد المؤقّت. */
    @Override
    public void onPause() {
        super.onPause();
        sVisible = false;
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
        try { if (screenRx != null) unregisterReceiver(screenRx); } catch (Throwable ignored) { }
        try { if (isFinishing()) AudioShade.cancel(getApplicationContext()); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private void stopAdhan() {
        stopAdhan(false);
    }

    private void stopAdhan(boolean force) {
        if (!adhanPlaying) return;
        if (!force) {
            long now = System.currentTimeMillis();
            if (now < adhanGraceUntil || now - screenEventAt < 4000) return;
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isInteractive()) return;
            } catch (Throwable ignored) { }
        }
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
                stopAdhan(true);
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
        // لا يوقف فقدانُ التركيز شيئًا: الإيقاف فقط بأزرار الصوت أو بسحب الستارة (شريط الحالة)
    }
}
