package com.zadalmuslim.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;

/**
 * لوحة الأذان على الشاشة المقفلة — جدولة أصلية لا تعتمد على الصفحة.
 *
 * الصفحة ترسل قائمة [[مللي, رقم_الصلاة], ...] للأيام القادمة عبر set().
 * نضبط منبّهًا دقيقًا للأذان التالي فقط ← AdhanScreenReceiver ← يفتح MainActivity فوق قفل الشاشة
 * (إشعار بملء الشاشة + فتح مباشر إن كان إذن «الظهور فوق التطبيقات» ممنوحًا).
 * صوت الأذان نفسه يبقى من إشعار الأذان الحالي؛ هذه الطبقة تعرض اللوحة فقط وبلا صوت.
 *
 * واجهة الصفحة (window.AdhanScreen): set(json) / stopSound() / fsi() / askFsi()
 */
public class AdhanScreen {
    static final String PREFS = "zad_adhscr";
    static final String K_LIST = "list";
    static final String ACTION_FIRE = "com.zadalmuslim.app.ADHAN_SCREEN";
    static final String EXTRA_IDX = "zad_adhan";
    static final int REQ_ALARM = 7401;
    static final int NID = 7411;

    private final Context ctx;

    public AdhanScreen(Context c) {
        this.ctx = c.getApplicationContext();
    }

    @JavascriptInterface
    public void set(String json) {
        try {
            new JSONArray(json == null ? "[]" : json);
            prefs(ctx).edit().putString(K_LIST, json == null ? "[]" : json).apply();
            schedule(ctx);
        } catch (Throwable ignored) {
        }
    }

    /** يوقف صوت إشعار الأذان (زر «إيقاف الأذان» في اللوحة). */
    @JavascriptInterface
    public void stopSound() {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) {
                for (StatusBarNotification n : nm.getActiveNotifications()) {
                    String ch = Build.VERSION.SDK_INT >= 26 && n.getNotification() != null
                            ? n.getNotification().getChannelId() : null;
                    if (ch != null && ch.startsWith("zad_adhan_")) nm.cancel(n.getTag(), n.getId());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** أندرويد 14+: هل يملك التطبيق إذن «الإشعارات بملء الشاشة». */
    @JavascriptInterface
    public boolean fsi() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                return nm.canUseFullScreenIntent();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    @JavascriptInterface
    public boolean askFsi() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 34) {
                i = new Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:" + ctx.getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.getPackageName()));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** يضبط منبّهًا دقيقًا لأقرب أذان قادم من القائمة المحفوظة. */
    static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent it = new Intent(c, AdhanScreenReceiver.class).setAction(ACTION_FIRE);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            long now = System.currentTimeMillis();
            long best = 0;
            int idx = -1;
            JSONArray a = new JSONArray(prefs(c).getString(K_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray e = a.getJSONArray(i);
                long ms = e.getLong(0);
                if (ms > now + 1000 && (best == 0 || ms < best)) {
                    best = ms;
                    idx = e.getInt(1);
                }
            }
            if (best == 0) {
                PendingIntent old = PendingIntent.getBroadcast(c, REQ_ALARM, it, fl);
                am.cancel(old);
                return;
            }
            it.putExtra(EXTRA_IDX, idx).putExtra("ms", best);
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ_ALARM, it, fl);
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (Build.VERSION.SDK_INT >= 23) {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, best, pi);
            }
        } catch (Throwable ignored) {
        }
    }
}
