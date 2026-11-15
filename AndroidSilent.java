package com.zadalmuslim.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

/**
 * الجسر الذي تبحث عنه الصفحة باسم window.AndroidSilent
 *   has()   هل مُنح إذن «الوصول إلى عدم الإزعاج»؟
 *   ask()   يفتح إعداد النظام لمنح الإذن
 *   start() يفعّل الصامت (يحفظ الوضع السابق)  -> true عند النجاح
 *   end()   يعيد وضع الهاتف السابق            -> true عند النجاح
 *
 * نستخدم وضع «المنبّهات فقط» حتى لا يُكتم منبّه الفجر، فتصمت المكالمات والإشعارات والرنين.
 */
public class AndroidSilent {
    private static final String PREFS = "zad_silent";
    private static final String KEY_PREV = "prev";   // الوضع السابق، -1 = لا شيء محفوظ
    private static final String KEY_OURS = "ours";   // الوضع الذي ضبطناه نحن

    private final Context ctx;

    public AndroidSilent(Context c) {
        this.ctx = c.getApplicationContext();
    }

    private NotificationManager nm() {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private SharedPreferences sp() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public boolean has() {
        try {
            return Build.VERSION.SDK_INT >= 23 && nm().isNotificationPolicyAccessGranted();
        } catch (Throwable t) {
            return false;
        }
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
    public boolean start() {
        try {
            if (!has()) return false;
            NotificationManager m = nm();
            int cur = m.getCurrentInterruptionFilter();
            int ours = NotificationManager.INTERRUPTION_FILTER_ALARMS;
            SharedPreferences s = sp();
            // لا نكتب فوق الوضع السابق إذا كنا نحن من فعّل الصامت أصلًا
            if (cur != ours || s.getInt(KEY_PREV, -1) == -1) {
                s.edit().putInt(KEY_PREV, cur == ours ? NotificationManager.INTERRUPTION_FILTER_ALL : cur).apply();
            }
            m.setInterruptionFilter(ours);
            s.edit().putInt(KEY_OURS, ours).apply();
            return m.getCurrentInterruptionFilter() == ours;
        } catch (Throwable t) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean end() {
        try {
            SharedPreferences s = sp();
            int prev = s.getInt(KEY_PREV, -1);
            int ours = s.getInt(KEY_OURS, -1);
            if (prev == -1) return true;                       // لم نفعّل شيئًا
            if (!has()) return false;
            NotificationManager m = nm();
            // إن غيّر المستخدم الوضع بنفسه أثناء الصلاة فلا نتدخل
            if (m.getCurrentInterruptionFilter() == ours) m.setInterruptionFilter(prev);
            s.edit().remove(KEY_PREV).remove(KEY_OURS).apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
