package com.zadalmuslim.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.webkit.JavascriptInterface;

import androidx.core.app.NotificationCompat;

/** جسر التنزيل الذكي للصفحة: window.ZadDl (update / end / net). */
public class ZadDl {
    private final Context ctx;

    public ZadDl(Context c) {
        ctx = c.getApplicationContext();
    }

    /** يبدأ الخدمة الأمامية أو يحدّث إشعار التقدّم. pct < 0 = شريط غير محدد (انتظار). */
    @JavascriptInterface
    public void update(String title, String text, int pct) {
        try {
            if (DlService.running) {
                android.app.Notification n = DlService.build(ctx, title, text, pct);
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (n != null && nm != null) nm.notify(DlService.NID, n);
                return;
            }
            Intent si = new Intent(ctx, DlService.class);
            si.putExtra("t", title);
            si.putExtra("x", text);
            si.putExtra("p", pct);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(si);
            else ctx.startService(si);
        } catch (Throwable ignored) { }
    }

    /** ينهي الخدمة ويعرض إشعارًا عاديًا بالنتيجة (إن لم يكن النص فارغًا). */
    @JavascriptInterface
    public void end(String title, String text) {
        DlService.stop(ctx);
        try {
            if (text == null || text.length() == 0) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            DlService.build(ctx, title, text, 100); // يُنشئ القناة
            int icon = ctx.getResources().getIdentifier("ic_stat_zad", "drawable", ctx.getPackageName());
            if (icon == 0) icon = android.R.drawable.stat_sys_download_done;
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, DlService.CH)
                    .setSmallIcon(icon)
                    .setContentTitle(title == null || title.length() == 0 ? "زاد المسلم" : title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            nm.notify(DlService.NID_DONE, b.build());
        } catch (Throwable ignored) { }
    }

    /** هل يوجد اتصال بشبكة (واي‑فاي/بيانات)؟ لا نشترط «التحقق» لأن بعض الهواتف لا تُتمّه. */
    @JavascriptInterface
    public boolean net() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }
}
