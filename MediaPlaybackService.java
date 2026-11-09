package com.zadalmuslim.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * خدمة تشغيل صوتي أمامية: تُظهر إشعار الوسائط (تشغيل/إيقاف/السابق/التالي) في الستارة
 * وعلى شاشة القفل، وتُبقي التطبيق حيًّا أثناء تشغيل التلاوة والأذكار والشاشة مطفأة.
 * الصوت نفسه يُشغَّل داخل الصفحة؛ هذه الخدمة تعرض الإشعار وتنقل أوامره إلى الصفحة.
 */
public class MediaPlaybackService extends Service {

    static final String CH = "zad_media";
    static final int NID = 7001;
    static final String A_PLAY = "zad.media.PLAY", A_PAUSE = "zad.media.PAUSE",
            A_PREV = "zad.media.PREV", A_NEXT = "zad.media.NEXT", A_STOP = "zad.media.STOP",
            A_REFRESH = "zad.media.REFRESH";

    // حالة مشتركة (تُحدَّث من جسر الصفحة)
    static volatile String title = "زاد المسلم", artist = "";
    static volatile boolean hasPrev, hasNext, playing, active;
    static volatile long posMs, durMs;
    static MediaPlaybackService inst;

    private MediaSession session;
    private PowerManager.WakeLock wl;
    private boolean inFg = false;

    /* ---------- واجهة ثابتة تستدعيها الصفحة ---------- */
    static void setMeta(Context c, String t, String a, boolean prev, boolean next) {
        title = t; artist = a; hasPrev = prev; hasNext = next; active = true;
        poke(c);
    }

    static void setState(Context c, boolean pl, long pos, long dur) {
        boolean changed = (pl != playing);
        playing = pl; posMs = pos; durMs = dur; active = true;
        if (changed || inst == null) poke(c); else inst.refreshState(false);
    }

    static void clear(Context c) {
        active = false; playing = false;
        if (inst != null) inst.shutdown();
    }

    private static void poke(Context c) {
        try {
            if (inst != null) { inst.refreshState(true); return; }
            if (!playing) return; // لا نبدأ الخدمة إلا عند بدء التشغيل فعلًا
            Intent i = new Intent(c, MediaPlaybackService.class).setAction(A_REFRESH);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    /* ---------- دورة حياة الخدمة ---------- */
    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "تشغيل التلاوة والأذكار",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        session = new MediaSession(this, "ZadAlMuslim");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.sendToJs("play", 0); }
            @Override public void onPause() { MainActivity.sendToJs("pause", 0); }
            @Override public void onSkipToNext() { MainActivity.sendToJs("next", 0); }
            @Override public void onSkipToPrevious() { MainActivity.sendToJs("prev", 0); }
            @Override public void onStop() { MainActivity.sendToJs("stop", 0); }
            @Override public void onSeekTo(long pos) { MainActivity.sendToJs("seek", pos); }
        });
        session.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zad:media");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (A_PLAY.equals(a)) MainActivity.sendToJs("play", 0);
        else if (A_PAUSE.equals(a)) MainActivity.sendToJs("pause", 0);
        else if (A_NEXT.equals(a)) MainActivity.sendToJs("next", 0);
        else if (A_PREV.equals(a)) MainActivity.sendToJs("prev", 0);
        else if (A_STOP.equals(a)) { MainActivity.sendToJs("stop", 0); shutdown(); return START_NOT_STICKY; }
        refreshState(true);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) { shutdown(); super.onTaskRemoved(rootIntent); }

    @Override
    public void onDestroy() {
        inst = null;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void shutdown() {
        active = false; playing = false;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        try { stopForeground(true); } catch (Exception ignored) { }
        inFg = false;
        stopSelf();
    }

    /* ---------- تحديث الجلسة والإشعار ---------- */
    void refreshState(boolean rebuild) {
        try {
            long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                    | PlaybackState.ACTION_SEEK_TO;
            if (hasPrev) acts |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            if (hasNext) acts |= PlaybackState.ACTION_SKIP_TO_NEXT;
            session.setPlaybackState(new PlaybackState.Builder().setActions(acts)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            posMs, playing ? 1f : 0f, SystemClock.elapsedRealtime()).build());
            if (rebuild) {
                session.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "زاد المسلم")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs > 0 ? durMs : -1).build());
            }
            if (wl != null) {
                if (playing && !wl.isHeld()) wl.acquire(6 * 60 * 60 * 1000L);
                else if (!playing && wl.isHeld()) wl.release();
            }
            if (!rebuild && inFg == playing) return;
            Notification n = build();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (playing) {
                if (Build.VERSION.SDK_INT >= 29)
                    startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(NID, n);
                inFg = true;
            } else {
                if (inFg) { stopForeground(false); inFg = false; }
                nm.notify(NID, n);
            }
        } catch (Exception ignored) { }
    }

    private PendingIntent act(String a, int code) {
        Intent i = new Intent(this, MediaPlaybackService.class).setAction(a);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, code, i, fl);
    }

    private Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        int small = getResources().getIdentifier("ic_stat_zad", "drawable", getPackageName());
        if (small == 0) small = android.R.drawable.ic_media_play;
        List<Integer> compact = new ArrayList<>();
        int idx = 0;
        if (hasPrev) { b.addAction(android.R.drawable.ic_media_previous, "السابق", act(A_PREV, 1)); compact.add(idx++); }
        b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "إيقاف مؤقت" : "تشغيل", act(playing ? A_PAUSE : A_PLAY, 2));
        compact.add(idx++);
        if (hasNext) { b.addAction(android.R.drawable.ic_media_next, "التالي", act(A_NEXT, 3)); compact.add(idx++); }
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "إغلاق", act(A_STOP, 4));
        int[] ci = new int[compact.size()];
        for (int i = 0; i < ci.length; i++) ci[i] = compact.get(i);

        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken()).setShowActionsInCompactView(ci))
                .setSmallIcon(small)
                .setContentTitle(title).setContentText(artist)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, fl))
                .setDeleteIntent(act(A_STOP, 4))
                .setOngoing(playing).setShowWhen(false).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        return b.build();
    }
}
