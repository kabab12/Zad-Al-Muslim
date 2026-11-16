package com.zadalmuslim.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * يستقبل منبّه بداية/نهاية الصمت، وكذلك إعادة تشغيل الهاتف وتحديث التطبيق وتغيّر الوقت أو المنطقة الزمنية،
 * ثم يصالح الحالة ويضبط المنبّه التالي.
 */
public class SilentReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            AndroidSilent.reconcile(context);
        } catch (Throwable ignored) {
        }
    }
}
