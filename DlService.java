package com.zadalmuslim.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * خدمة أمامية للتنزيل الذكي: تُبقي التطبيق حيًّا والمعالج والواي‑فاي مستيقظَين أثناء تنزيل الصوتيات
 * حتى لو خرجتَ من التطبيق أو أُطفئت الشاشة، وتعرض إشعار تقدّم مع زر «إيقاف».
 * وتنبض كل ثانيتين إلى الصفحة (__dlTick) لأن مؤقّتات الصفحة تُبطَّأ في الخلفية.
 */
public class DlService extends Service {
    static final String CH = "zad_dl";
    static final int NID = 7210;
    static final int NID_DONE = 7211;
    static final String ACTION_STOP = "com.zadalmuslim.app.STOP_DL";
    static volatile boolean running = false;

    private PowerManager.WakeLock wl;
    private android.net.wifi.WifiManager.WifiLock wf;
    private Handler h;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try { MainActivity.evalJs("window.__dlTick&&window.__dlTick()"); } catch (Throwable ignored) { }
            if (running && h != null) h.postDelayed(this, 2000);
        }
    };

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String t = i != null ? i.getStringExtra("t") : null;
        String x = i != null ? i.getStringExtra("x") : null;
        int p = i != null ? i.getIntExtra("p", -1) : -1;
        boolean ok = false;
        try {
            Notification n = build(this, t, x, p);
            if (n != null) {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(NID, n);
                }
                ok = true;
            }
        } catch (Throwable ignored) {
        }
        if (!ok) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        acquire();
        if (h == null) h = new Handler(Looper.getMainLooper());
        h.removeCallbacks(tick);
        h.postDelayed(tick, 2000);
        return START_NOT_STICKY;
    }

    private void acquire() {
        try {
            if (wl == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zad:dl");
                    wl.setReferenceCounted(false);
                }
            }
            if (wl != null && !wl.isHeld()) wl.acquire(6L * 60L * 60L * 1000L);
        } catch (Throwable ignored) { }
        try {
            if (wf == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wf = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zad:dl-wifi");
                    wf.setReferenceCounted(false);
                }
            }
            if (wf != null && !wf.isHeld()) wf.acquire();
        } catch (Throwable ignored) { }
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (h != null) h.removeCallbacks(tick); } catch (Throwable ignored) { }
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) { }
        try { if (wf != null && wf.isHeld()) wf.release(); } catch (Throwable ignored) { }
        try { stopForeground(true); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    static Notification build(Context c, String title, String text, int pct) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return null;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                NotificationChannel ch = new NotificationChannel(CH, "تنزيل الصوتيات", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            int icon = c.getResources().getIdentifier("ic_stat_zad", "drawable", c.getPackageName());
            if (icon == 0) icon = android.R.drawable.stat_sys_download;
            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
            Intent stop = new Intent(c, AudioStopReceiver.class);
            stop.setAction(ACTION_STOP);
            PendingIntent stopPi = PendingIntent.getBroadcast(c, 7212, stop, fl);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                    .setSmallIcon(icon)
                    .setContentTitle(title == null || title.length() == 0 ? "زاد المسلم — تنزيل" : title)
                    .setContentText(text == null ? "" : text)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPi);
            if (pct >= 0) b.setProgress(100, Math.min(100, pct), false);
            else b.setProgress(100, 0, true);
            Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (open != null) b.setContentIntent(PendingIntent.getActivity(c, 7213, open, fl));
            return b.build();
        } catch (Throwable t) {
            return null;
        }
    }

    static void stop(Context c) {
        running = false;
        try { c.stopService(new Intent(c, DlService.class)); } catch (Throwable ignored) { }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NID);
        } catch (Throwable ignored) { }
    }
}
