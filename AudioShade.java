package com.zadalmuslim.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.webkit.JavascriptInterface;

import androidx.core.app.NotificationCompat;

/**
 * إشعار «⏹ إيقاف» في الستارة أثناء تشغيل القرآن أو أذكار حصن المسلم.
 * الضغط عليه (أو سحبه) يوقف الصوت نهائيًا من داخل الصفحة دون فتح التطبيق.
 * الصفحة تستدعي window.ZadShade.show(عنوان) و hide().
 */
public class AudioShade {
    static final String CH = "zad_audio_stop";
    static final int NID = 7203;
    static final String ACTION_STOP = "com.zadalmuslim.app.STOP_AUDIO";

    private final Context ctx;

    public AudioShade(Context c) {
        this.ctx = c.getApplicationContext();
    }

    @JavascriptInterface
    public void show(String title) {
        post(ctx, title);
    }

    @JavascriptInterface
    public void hide() {
        cancel(ctx);
    }

    /** الصفحة تخبرنا أن الصوت يعمل فعلًا: نمسك قفل CPU جزئيًا (يمنع نوم المعالج والشاشة مطفأة)، ونفلته عند الإيقاف. */
    @JavascriptInterface
    public void setPlaying(boolean on) {
        wake(ctx, on);
    }

    private static android.os.PowerManager.WakeLock sWl;
    private static android.net.wifi.WifiManager.WifiLock sWf;

    static synchronized void wake(Context c, boolean on) {
        try {
            if (on) {
                if (sWl == null) {
                    android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
                    if (pm == null) return;
                    sWl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "zad:audio");
                    sWl.setReferenceCounted(false);
                }
                if (!sWl.isHeld()) sWl.acquire(6L * 60L * 60L * 1000L);
            } else if (sWl != null && sWl.isHeld()) {
                sWl.release();
            }
        } catch (Throwable ignored) {
        }
        // قفل واي‑فاي: يمنع النظام من إبطاء/إيقاف الواي‑فاي أثناء التشغيل والشاشة مطفأة، فلا ينقطع البث
        try {
            if (on) {
                if (sWf == null) {
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                            c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        sWf = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zad:audio-wifi");
                        sWf.setReferenceCounted(false);
                    }
                }
                if (sWf != null && !sWf.isHeld()) sWf.acquire();
            } else if (sWf != null && sWf.isHeld()) {
                sWf.release();
            }
        } catch (Throwable ignored) {
        }
    }

    static void post(Context c, String title) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                NotificationChannel ch = new NotificationChannel(CH, "إيقاف التشغيل الصوتي",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            int icon = c.getResources().getIdentifier("ic_stat_zad", "drawable", c.getPackageName());
            if (icon == 0) icon = android.R.drawable.ic_media_pause;

            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;

            Intent stop = new Intent(c, AudioStopReceiver.class);
            stop.setAction(ACTION_STOP);
            PendingIntent stopPi = PendingIntent.getBroadcast(c, 7204, stop, fl);

            String t = (title == null || title.length() == 0) ? "زاد المسلم" : title;
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                    .setSmallIcon(icon)
                    .setContentTitle(t)
                    .setContentText("التشغيل الصوتي جارٍ — اضغط «إيقاف» لإنهائه")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setDeleteIntent(stopPi)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPi);

            Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (open != null) {
                b.setContentIntent(PendingIntent.getActivity(c, 7205, open, fl));
            }
            nm.notify(NID, b.build());
        } catch (Throwable ignored) {
        }
    }

    static void cancel(Context c) {
        wake(c, false);
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NID);
        } catch (Throwable ignored) {
        }
    }
}
