package com.zadalmuslim.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** زر «إيقاف» في الستارة: يزيل الإشعار ويأمر الصفحة بإيقاف كل صوت، دون فتح التطبيق. */
public class AudioStopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && DlService.ACTION_STOP.equals(intent.getAction())) {
            try { DlService.stop(context); } catch (Throwable ignored) { }
            try { MainActivity.evalJs("window.__zadDlStop&&window.__zadDlStop()"); } catch (Throwable ignored) { }
            return;
        }
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
