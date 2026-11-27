package com.zadalmuslim.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import androidx.core.app.NotificationCompat;

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
    static final String K_TRIES = "tries";      // عدد محاولات الفك الفاشلة المتتالية
    static final String K_SET = "setours";      // ضبطنا «المنبّهات فقط» ولم نتأكد بعد من الاستعادة
    static final String K_LASTWS = "lastws";    // بداية آخر نافذة فعّلناها (لا نعيد تفعيلها إن ألغاها المستخدم يدويًا)
    static final String ACTION_TICK = "com.zadalmuslim.app.SILENT_TICK";
    static final int REQ_TICK = 7301;
    static final String CH_MSG = "zad_silent_msg";   // قناة رسالة بدء الصمت وانتهائه (هادئة بلا صوت)
    static final int NID_MSG = 7311;

    /** آيات وأحاديث في فضل الصلاة: {النص، المصدر}. تُعرض مع إشعار تفعيل الصمت وفكّه */
    private static final String[][] MSG_GEN = {
        {"إِنَّ الصَّلَاةَ تَنْهَىٰ عَنِ الْفَحْشَاءِ وَالْمُنكَرِ", "سورة العنكبوت: 45"},
        {"حَافِظُوا عَلَى الصَّلَوَاتِ وَالصَّلَاةِ الْوُسْطَىٰ وَقُومُوا لِلَّهِ قَانِتِينَ", "سورة البقرة: 238"},
        {"قَدْ أَفْلَحَ الْمُؤْمِنُونَ * الَّذِينَ هُمْ فِي صَلَاتِهِمْ خَاشِعُونَ", "سورة المؤمنون: 1-2"},
        {"إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَّوْقُوتًا", "سورة النساء: 103"},
        {"وَاسْتَعِينُوا بِالصَّبْرِ وَالصَّلَاةِ وَإِنَّهَا لَكَبِيرَةٌ إِلَّا عَلَى الْخَاشِعِينَ", "سورة البقرة: 45"},
        {"وَأَقِمِ الصَّلَاةَ لِذِكْرِي", "سورة طه: 14"},
        {"وَأْمُرْ أَهْلَكَ بِالصَّلَاةِ وَاصْطَبِرْ عَلَيْهَا", "سورة طه: 132"},
        {"أَرَأَيْتُمْ لَوْ أَنَّ نَهَرًا بِبَابِ أَحَدِكُمْ يَغْتَسِلُ فِيهِ كُلَّ يَوْمٍ خَمْسًا، مَا تَقُولُ ذَلِكَ يُبْقِي مِنْ دَرَنِهِ؟ قَالُوا: لَا يُبْقِي مِنْ دَرَنِهِ شَيْئًا. قَالَ: فَذَلِكَ مَثَلُ الصَّلَوَاتِ الْخَمْسِ، يَمْحُو اللَّهُ بِهِنَّ الْخَطَايَا", "متفق عليه"},
        {"الصَّلَوَاتُ الْخَمْسُ، وَالْجُمُعَةُ إِلَى الْجُمُعَةِ، وَرَمَضَانُ إِلَى رَمَضَانَ، مُكَفِّرَاتٌ مَا بَيْنَهُنَّ إِذَا اجْتَنَبَ الْكَبَائِرَ", "رواه مسلم"},
        {"أَقْرَبُ مَا يَكُونُ الْعَبْدُ مِنْ رَبِّهِ وَهُوَ سَاجِدٌ، فَأَكْثِرُوا الدُّعَاءَ", "رواه مسلم"},
        {"إِنَّ أَوَّلَ مَا يُحَاسَبُ بِهِ الْعَبْدُ يَوْمَ الْقِيَامَةِ مِنْ عَمَلِهِ صَلَاتُهُ، فَإِنْ صَلُحَتْ فَقَدْ أَفْلَحَ وَأَنْجَحَ", "رواه الترمذي والنسائي"},
        {"الصَّلَاةُ نُورٌ", "رواه مسلم"},
        {"جُعِلَتْ قُرَّةُ عَيْنِي فِي الصَّلَاةِ", "رواه النسائي وأحمد"},
        {"سُئِلَ النَّبِيُّ ﷺ: أَيُّ الْعَمَلِ أَحَبُّ إِلَى اللَّهِ؟ قَالَ: الصَّلَاةُ عَلَى وَقْتِهَا", "متفق عليه"}
    };
    private static final String[] M_FAJR1 = {"رَكْعَتَا الْفَجْرِ خَيْرٌ مِنَ الدُّنْيَا وَمَا فِيهَا", "رواه مسلم"};
    private static final String[] M_FAJR2 = {"مَنْ صَلَّى الصُّبْحَ فَهُوَ فِي ذِمَّةِ اللَّهِ", "رواه مسلم"};
    private static final String[] M_BURDAYN = {"مَنْ صَلَّى الْبَرْدَيْنِ دَخَلَ الْجَنَّةَ", "متفق عليه"};
    private static final String[] M_JUMUA = {"يَا أَيُّهَا الَّذِينَ آمَنُوا إِذَا نُودِيَ لِلصَّلَاةِ مِن يَوْمِ الْجُمُعَةِ فَاسْعَوْا إِلَىٰ ذِكْرِ اللَّهِ وَذَرُوا الْبَيْعَ", "سورة الجمعة: 9"};

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

    /* ---------------- رسالة بدء الصمت وانتهائه ---------------- */

    /** اسم النافذة التي بدأت عند ws (الفجر/الظهر/…/الجمعة) أو «الصلاة» إن لم تُوجد */
    private static String windowName(SharedPreferences sp, long ws) {
        try {
            JSONArray a = windows(sp);
            for (int i = 0; i < a.length(); i++) {
                JSONArray w = a.optJSONArray(i);
                if (w != null && w.optLong(0, 0L) == ws) {
                    String n = w.optString(2, "");
                    if (n.length() > 0) return n;
                }
            }
        } catch (Throwable ignored) {
        }
        return "الصلاة";
    }

    /** يختار آية أو حديثًا بالتناوب (يتغيّر بتغيّر وقت الصلاة)، وللفجر والعصر والجمعة نصوص تخصّها */
    private static String[] pick(String name, long ws, int shift) {
        java.util.ArrayList<String[]> l = new java.util.ArrayList<String[]>();
        for (String[] x : MSG_GEN) l.add(x);
        if ("الفجر".equals(name)) {
            l.add(M_FAJR1);
            l.add(M_FAJR2);
            l.add(M_BURDAYN);
        } else if ("العصر".equals(name)) {
            l.add(M_BURDAYN);
        } else if ("الجمعة".equals(name)) {
            l.add(M_JUMUA);
        }
        int n = l.size();
        int idx = (int) (((ws / 60000L) + shift) % n);
        if (idx < 0) idx += n;
        return l.get(idx);
    }

    /** إشعار هادئ (بلا صوت): عند التفعيل «تم تفعيل الوضع الصامت لأداء صلاة …» وعند الفك «انتهى وقت الصمت»، مع آية أو حديث في فضل الصلاة */
    private static void announce(Context c, SharedPreferences sp, long ws, boolean start, boolean foreign) {
        try {
            NotificationManager m = nm(c);
            if (m == null) return;
            String name = windowName(sp, ws);
            String[] q = pick(name, ws, start ? 0 : 1);
            String title;
            if (start) {
                title = foreign ? ("حان وقت صلاة " + name + " — هاتفك صامت أصلًا")
                        : ("تم تفعيل الوضع الصامت لأداء صلاة " + name);
            } else {
                title = foreign ? ("انتهى وقت صلاة " + name + " — تقبّل الله طاعتك")
                        : "انتهى وقت الصمت وعاد هاتفك لوضعه — تقبّل الله صلاتك";
            }
            String body = q[0] + "\n— " + q[1];
            if (Build.VERSION.SDK_INT >= 26 && m.getNotificationChannel(CH_MSG) == null) {
                NotificationChannel ch = new NotificationChannel(CH_MSG, "رسالة الوضع الصامت للصلاة",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                m.createNotificationChannel(ch);
            }
            int icon = c.getResources().getIdentifier("ic_stat_zad", "drawable", c.getPackageName());
            if (icon == 0) icon = android.R.drawable.ic_lock_silent_mode;
            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_MSG)
                    .setSmallIcon(icon)
                    .setContentTitle(title)
                    .setContentText(q[0])
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (open != null) b.setContentIntent(PendingIntent.getActivity(c, REQ_TICK + 2, open, fl));
            m.notify(NID_MSG, b.build());   // المعرّف نفسه: رسالة الفك تحلّ محل رسالة البدء
        } catch (Throwable ignored) {
        }
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
                // إن كان الوضع الحالي «المنبّهات فقط» وكنا نحن من ضبطه سابقًا ولم تتأكد استعادته،
                // فهو عالق من فكٍّ فاشل (وليس اختيار المستخدم): نعتبر الأصل «الكل» لنفكّه في نهاية النافذة
                boolean stuck = (cur == ours) && s.getBoolean(K_SET, false);
                // لو كان المستخدم أصلًا في «المنبّهات فقط» أو «صمت تام» فلا نغيّر شيئًا ولا نستعيد شيئًا
                boolean foreign = !stuck && (cur == ours || cur == NotificationManager.INTERRUPTION_FILTER_NONE);
                int keep = (cur == NotificationManager.INTERRUPTION_FILTER_UNKNOWN || stuck)
                        ? NotificationManager.INTERRUPTION_FILTER_ALL : cur;
                s.edit().putInt(K_PREV, keep).putBoolean(K_FOREIGN, foreign).apply();
            }
            s.edit().putInt(K_OURS, ours).apply();
            if (s.getBoolean(K_FOREIGN, false)) return true;
            if (cur != ours) {
                s.edit().putBoolean(K_SET, true).apply();
                m.setInterruptionFilter(ours);
            }
            // بعض الهواتف (شاومي/سامسونج) تُرجع قيمة مختلفة عند القراءة بعد الضبط، فلا نعتمد عليها:
            // يكفي أن الأمر نُفّذ بلا استثناء، ونحتفظ بالحالة لنفكّها في وقتها
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ينتظر قليلًا (حتى ~1.2 ثانية) ليأخذ النظام الأمر، ويُرجع القراءة الأخيرة */
    private static int waitFilter(NotificationManager m, int want) {
        int cur = m.getCurrentInterruptionFilter();
        for (int i = 0; i < 8 && cur != want; i++) {
            try {
                Thread.sleep(150);
            } catch (Throwable ignored) {
            }
            cur = m.getCurrentInterruptionFilter();
        }
        return cur;
    }

    static boolean endNow(Context c) {
        SharedPreferences s = prefs(c);
        int prev = s.getInt(K_PREV, -1);
        boolean foreign = s.getBoolean(K_FOREIGN, false);
        if (prev == -1 || foreign) {
            s.edit().remove(K_PREV).remove(K_OURS).remove(K_FOREIGN).remove(K_TRIES).remove(K_SET).apply();
            return true;
        }
        try {
            if (!hasAccess(c)) {
                // لا إذن فلا فائدة من إعادة المحاولة؛ نمسح الحالة حتى لا ندور للأبد
                s.edit().remove(K_PREV).remove(K_OURS).remove(K_FOREIGN).remove(K_TRIES).apply();
                return false;
            }
            NotificationManager m = nm(c);
            int ours = NotificationManager.INTERRUPTION_FILTER_ALARMS;
            int tries = s.getInt(K_TRIES, 0) + 1;
            // كان الفك يُنفَّذ فقط إن اختلفت القراءة عن الأصل، وكنا نمسح الحالة ونعلن «انتهى الصمت» دون تحقق؛
            // فإن لم يأخذ النظام الأمر (أو أرجع قراءة خاطئة) بقي الهاتف صامتًا. الآن نُرسل الأمر دائمًا ونتحقق.
            m.setInterruptionFilter(prev);
            int cur = waitFilter(m, prev);
            if (cur == ours && tries >= 2) {
                // ما زال عالقًا بعد محاولة سابقة: نعيد «الكل» صراحةً
                m.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                cur = waitFilter(m, NotificationManager.INTERRUPTION_FILTER_ALL);
            }
            boolean stuck = (cur == ours);
            if (!stuck || tries >= 5) {
                s.edit().remove(K_PREV).remove(K_OURS).remove(K_FOREIGN).remove(K_TRIES).remove(K_SET).apply();
                return true;
            }
            // لم يُفكّ: نُبقي الحالة ونعيد المحاولة بعد 30 ثانية (حتى 5 مرات)
            s.edit().putInt(K_TRIES, tries).apply();
            return false;
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
        boolean stale = sp.getInt(K_PREV, -1) != -1;   // حالة محفوظة لم تُفكّ (مثلًا بعد فشل سابق)
        boolean retry = false;
        if (w != null) {
            // داخل نافذة: نفعّل مرة واحدة فقط لكل نافذة (فإن ألغاه المستخدم يدويًا لا نعيده)
            if (!active && sp.getLong(K_LASTWS, -1L) != w[0]) {
                if (startNow(c)) {
                    sp.edit().putBoolean(K_ACTIVE, true).putLong(K_LASTWS, w[0]).apply();
                    announce(c, sp, w[0], true, sp.getBoolean(K_FOREIGN, false));
                } else {
                    retry = hasAccess(c);   // فشل عابر: نعيد المحاولة بعد قليل داخل النافذة نفسها
                }
            }
        } else if (active || stale) {
            // خارج أي نافذة: نفكّ، ولو لم يكن «نشطًا» لكن بقيت حالة محفوظة (كان هذا سبب بقاء الهاتف صامتًا)
            boolean foreignNow = sp.getBoolean(K_FOREIGN, false);
            long lastWs = sp.getLong(K_LASTWS, -1L);
            if (endNow(c)) {
                sp.edit().putBoolean(K_ACTIVE, false).apply();
                if (active) announce(c, sp, lastWs, false, foreignNow);
            } else {
                retry = hasAccess(c);
            }
        }
        arm(c, sp, now, retry);
    }

    private static PendingIntent showIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, REQ_TICK + 1, i, fl);
    }

    private static void arm(Context c, SharedPreferences sp, long now, boolean retry) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = tickIntent(c);
        try {
            am.cancel(pi);
        } catch (Throwable ignored) {
        }
        long next = nextBoundary(sp, now);
        long at = next > 0 ? next + 300L : -1L; // بعد الحدّ بجزء من الثانية ليكون now >= الحدّ عند الاستيقاظ
        if (retry) {
            long r = now + 30000L;
            if (at < 0 || r < at) at = r;
        }
        if (at <= 0) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                // بلا إذن «المنبّهات والتذكيرات» كان المنبّه غير دقيق ويتأخر دقائق في Doze.
                // منبّه الساعة مستثنى من Doze ويعمل بالدقة نفسها دون إذن.
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showIntent(c)), pi);
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

    /** «فك الصمت الآن»: يعيد الصوت فورًا ويمسح أي حالة عالقة (للطوارئ إن علق الهاتف في الصمت) */
    @JavascriptInterface
    public boolean release() {
        try {
            prefs(ctx).edit().remove(K_PREV).remove(K_OURS).remove(K_FOREIGN).remove(K_TRIES).remove(K_SET).putBoolean(K_ACTIVE, false).apply();
            if (!hasAccess(ctx)) return false;
            nm(ctx).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** نص تشخيصي عن حالة الجدولة */
    @JavascriptInterface
    public String status() {
        StringBuilder sb = new StringBuilder();
        try {
            SharedPreferences sp = prefs(ctx);
            long now = System.currentTimeMillis();
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            boolean exact = Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms());
            sb.append("إذن عدم الإزعاج: ").append(hasAccess(ctx) ? "ممنوح" : "غير ممنوح").append("\n");
            sb.append("المنبّهات الدقيقة: ").append(exact ? "مسموحة" : "غير مسموحة (نستعمل منبّه الساعة)").append("\n");
            sb.append("وضع الهاتف الآن (رقم): ").append(hasAccess(ctx) ? nm(ctx).getCurrentInterruptionFilter() : -1).append("\n");
            sb.append("نشط: ").append(sp.getBoolean(K_ACTIVE, false)).append(" | محفوظ سابقًا: ").append(sp.getInt(K_PREV, -1)).append("\n");
            sb.append("عدد نوافذ الصمت المجدولة: ").append(windows(sp).length()).append("\n");
            long nb = nextBoundary(sp, now);
            sb.append("أقرب حدّ قادم: ").append(nb > 0 ? f.format(new java.util.Date(nb)) : "لا يوجد").append("\n");
            sb.append("الآن: ").append(f.format(new java.util.Date(now)));
        } catch (Throwable t) {
            sb.append("خطأ: ").append(t);
        }
        return sb.toString();
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
