package com.zadalmuslim.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;

/**
 * الصامت أثناء الصلاة — جدولة أصلية (لا تعتمد على مؤقّت الصفحة).
 *
 * الصفحة تُرسل قائمة نوافذ الصمت [[بداية_مللي, نهاية_مللي, "الاسم"], ...] للأيام القادمة عبر schedule().
 * ثم يتولى النظام الباقي: منبّه دقيق (AlarmManager) عند كل بداية/نهاية ← SilentReceiver ← reconcile().
 * reconcile() دالة «مصالحة»: تنظر إلى الوقت الحالي وتضبط الحالة الصحيحة (تفعيل أو فك)، فتصلح نفسها
 * إن فات منبّه أو أُعيد تشغيل الهاتف أو تغيّر الوقت، ولا يعلق الهاتف في الصمت.
 *
 * واجهة الصفحة (window.AndroidSilent):
 *   has() / ask()          إذن «الوصول إلى عدم الإزعاج»
 *   schedule(json)         يحفظ النوافذ ويصالح فورًا ويضبط المنبّه التالي
 *   reconcile()            مصالحة فورية
 *   start() / end()        للتجربة اليدوية فقط
 */
public class AndroidSilent {
    static final String PREFS = "zad_silent";
    static final String K_PREV = "prev";        // وضع الهاتف قبل أن نغيّره، -1 = لا شيء محفوظ
    static final String K_OURS = "ours";        // الوضع الذي ضبطناه نحن
    static final String K_FOREIGN = "foreign";  // كان المستخدم نفسه في وضع مساوٍ أو أشد: لا نلمسه
    static final String K_WIN = "win";          // نوافذ الصمت (JSON)
    static final String K_ACTIVE = "active";    // هل نحن الآن داخل نافذة فعّلناها
    static final String K_LASTWS = "lastws";    // بداية آخر نافذة فعّلناها (لا نعيد تفعيلها إن ألغاها المستخدم يدويًا)
    static final String ACTION_TICK = "com.zadalmuslim.app.SILENT_TICK";
    static final int REQ_TICK = 7301;

    private final Context ctx;

    public AndroidSilent(Context c) {
        this.ctx = c.getApplicationContext();
    }

    /* ---------------- أدوات ---------------- */

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean hasAccess(Context c) {
        try {
            return Build.VERSION.SDK_INT >= 23 && nm(c).isNotificationPolicyAccessGranted();
        } catch (Throwable t) {
            return false;
        }
    }

    private static JSONArray windows(SharedPreferences sp) {
        try {
            return new JSONArray(sp.getString(K_WIN, "[]"));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    /** النافذة الجارية الآن {بداية، نهاية} أو null */
    private static long[] current(SharedPreferences sp, long now) {
        long[] best = null;
        JSONArray a = windows(sp);
        for (int i = 0; i < a.length(); i++) {
            JSONArray w = a.optJSONArray(i);
            if (w == null) continue;
            long s = w.optLong(0, 0L);
            long e = w.optLong(1, 0L);
            if (s <= now && now < e) {
                if (best == null || e > best[1]) best = new long[]{s, e};
            }
        }
        return best;
    }

    /** أقرب حدّ قادم (بداية نافذة أو نهايتها) بعد now، أو -1 */
    private static long nextBoundary(SharedPreferences sp, long now) {
        long next = -1L;
        JSONArray a = windows(sp);
        for (int i = 0; i < a.length(); i++) {
            JSONArray w = a.optJSONArray(i);
            if (w == null) continue;
            long s = w.optLong(0, 0L);
            long e = w.optLong(1, 0L);
            long b = -1L;
            if (s > now) b = s;
            else if (e > now) b = e;
            if (b > 0 && (next < 0 || b < next)) next = b;
        }
        return next;
    }

    private static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, SilentReceiver.class);
        i.setAction(ACTION_TICK);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ_TICK, i, fl);
    }

    /* ---------------- التفعيل والفك ---------------- */

    static boolean startNow(Context c) {
        try {
            if (!hasAccess(c)) return false;
            NotificationManager m = nm(c);
            int cur = m.getCurrentInterruptionFilter();
            int ours = NotificationManager.INTERRUPTION_FILTER_ALARMS;
            SharedPreferences s = prefs(c);
            if (s.getInt(K_PREV, -1) == -1) {
                // لو كان المستخدم أصلًا في «المنبّهات فقط» أو «صمت تام» فلا نغيّر شيئًا ولا نستعيد شيئًا
                boolean foreign = (cur == ours || cur == NotificationManager.INTERRUPTION_FILTER_NONE);
                int keep = (cur == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
                        ? NotificationManager.INTERRUPTION_FILTER_ALL : cur;
                s.edit().putInt(K_PREV, keep).putBoolean(K_FOREIGN, foreign).apply();
            }
            s.edit().putInt(K_OURS, ours).apply();
            if (s.getBoolean(K_FOREIGN, false)) return true;
            if (cur != ours) m.setInterruptionFilter(ours);
            return m.getCurrentInterruptionFilter() == ours;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean endNow(Context c) {
        try {
            SharedPreferences s = prefs(c);
            int prev = s.getInt(K_PREV, -1);
            int ours = s.getInt(K_OURS, -1);
            boolean foreign = s.getBoolean(K_FOREIGN, false);
            // نمسح الحالة أولًا حتى لا نعلق أبدًا، حتى لو فشلت الاستعادة
            s.edit().remove(K_PREV).remove(K_OURS).remove(K_FOREIGN).apply();
            if (prev == -1 || foreign) return true;
            if (!hasAccess(c)) return false;
            NotificationManager m = nm(c);
            // إن غيّر المستخدم الوضع بنفسه أثناء الصلاة فلا نتدخل
            if (m.getCurrentInterruptionFilter() == ours) m.setInterruptionFilter(prev);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------------- المصالحة والمنبّه ---------------- */

    static synchronized void reconcile(Context ctx) {
        Context c = ctx.getApplicationContext();
        SharedPreferences sp = prefs(c);
        long now = System.currentTimeMillis();
        long[] w = current(sp, now);
        boolean active = sp.getBoolean(K_ACTIVE, false);
        if (w != null) {
            // داخل نافذة: نفعّل مرة واحدة فقط لكل نافذة (فإن ألغاه المستخدم يدويًا لا نعيده)
            if (!active && sp.getLong(K_LASTWS, -1L) != w[0]) {
                if (startNow(c)) {
                    sp.edit().putBoolean(K_ACTIVE, true).putLong(K_LASTWS, w[0]).apply();
                }
            }
        } else if (active) {
            endNow(c);
            sp.edit().putBoolean(K_ACTIVE, false).apply();
        }
        arm(c, sp, now);
    }

    private static void arm(Context c, SharedPreferences sp, long now) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = tickIntent(c);
        try {
            am.cancel(pi);
        } catch (Throwable ignored) {
        }
        long next = nextBoundary(sp, now);
        if (next <= 0) return;
        long at = next + 1500L; // بعد الحدّ بقليل ليكون now >= الحدّ عند الاستيقاظ
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------------- واجهة الصفحة ---------------- */

    @JavascriptInterface
    public boolean has() {
        return hasAccess(ctx);
    }

    @JavascriptInterface
    public boolean ask() {
        try {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean schedule(String json) {
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            prefs(ctx).edit().putString(K_WIN, a.toString()).apply();
            reconcile(ctx);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @JavascriptInterface
    public void reconcile() {
        try {
            reconcile(ctx);
        } catch (Throwable ignored) {
        }
    }

    /** للتجربة اليدوية فقط (زر «جرّب») */
    @JavascriptInterface
    public boolean start() {
        return startNow(ctx);
    }

    @JavascriptInterface
    public boolean end() {
        return endNow(ctx);
    }
}
