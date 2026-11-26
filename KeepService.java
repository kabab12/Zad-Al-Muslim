package com.zadalmuslim.app;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * خدمة أمامية تُبقي التطبيق حيًّا ما دامت جلسة القرآن/الحصن قائمة (حتى وهي متوقفة مؤقتًا)،
 * فيعمل زر «تشغيل» في الستارة دون الحاجة لفتح التطبيق، ولا يقتل الهاتف العملية أثناء الإيقاف.
 * تستعمل إشعار «إيقاف» نفسه (AudioShade).
 */
public class KeepService extends Service {
    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String t = i != null ? i.getStringExtra("t") : null;
        boolean ok = false;
        try {
            Notification n = AudioShade.build(this, t);
            if (n != null) {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(AudioShade.NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(AudioShade.NID, n);
                }
                ok = true;
            }
        } catch (Throwable ignored) {
        }
        if (!ok) {
            try { AudioShade.notifyPlain(this, t); } catch (Throwable ignored) { }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { stopForeground(true); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
