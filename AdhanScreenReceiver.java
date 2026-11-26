package com.zadalmuslim.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

/**
 * عند وقت الأذان: يفتح التطبيق على لوحة الأذان فوق قفل الشاشة ويشغّل الشاشة.
 * وعند إعادة التشغيل أو تغيّر الوقت: يعيد ضبط المنبّه التالي من القائمة المحفوظة.
 */
public class AdhanScreenReceiver extends BroadcastReceiver {
    static final String CH = "zad_adhan_screen";
    private static final String[] NAMES = {"الفجر", "", "الظهر", "العصر", "المغرب", "العشاء"};

    @Override
    public void onReceive(Context c, Intent in) {
        try {
            if (in != null && AdhanScreen.ACTION_FIRE.equals(in.getAction())) {
                int idx = in.getIntExtra(AdhanScreen.EXTRA_IDX, -1);
                long ms = in.getLongExtra("ms", 0);
                long d = System.currentTimeMillis() - ms;
                // اللوحة لا تظهر إلا في وقت الأذان نفسه (حتى 90 ثانية تأخيرًا كحدّ أقصى)؛ بعد ذلك لا تُعرض
                if (idx >= 0 && d > -5000 && d < 90 * 1000L) {
                    if (MainActivity.sVisible) MainActivity.fireAdhanJs(idx);
                    else show(c, idx);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            AdhanScreen.schedule(c);
        } catch (Throwable ignored) {
        }
    }

    private void show(Context c, int idx) {
        // يشغّل الشاشة فورًا مهما كانت حالة الهاتف (مطفأة/مقفلة/Doze)
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                @SuppressWarnings("deprecation")
                android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | android.os.PowerManager.ON_AFTER_RELEASE, "zad:adhanscreen");
                wl.acquire(20 * 1000L);
            }
        } catch (Throwable ignored) {
        }
        Intent open = new Intent(c, MainActivity.class)
                .setAction("com.zadalmuslim.app.OPEN_ADHAN")
                .putExtra(AdhanScreen.EXTRA_IDX, idx)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(c, 7410 + idx, open, fl);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "لوحة الأذان", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("تعرض لوحة الأذان على الشاشة (بلا صوت؛ صوت الأذان من قناة الأذان)");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        int small = c.getResources().getIdentifier("ic_stat_zad", "drawable", c.getPackageName());
        if (small == 0) small = android.R.drawable.ic_lock_idle_alarm;
        String nm1 = idx >= 0 && idx < NAMES.length ? NAMES[idx] : "";
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                .setSmallIcon(small)
                .setContentTitle("حان الآن أذان " + nm1)
                .setContentText("اضغط لعرض لوحة الأذان")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setTimeoutAfter(4 * 60 * 1000L)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true);
        nm.notify(AdhanScreen.NID, b.build());
        // إن كان إذن «الظهور فوق التطبيقات» ممنوحًا يُسمح بفتح الشاشة مباشرة من الخلفية
        try {
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)) c.startActivity(open);
        } catch (Throwable ignored) {
        }
    }
}
