package com.zadalmuslim.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** زر «إيقاف» في الستارة: يزيل الإشعار ويأمر الصفحة بإيقاف كل صوت، دون فتح التطبيق. */
public class AudioStopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            AudioShade.cancel(context);
        } catch (Throwable ignored) {
        }
        try {
            MainActivity.stopAudioFromShade();
        } catch (Throwable ignored) {
        }
    }
}
