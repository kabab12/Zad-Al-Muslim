package com.zadalmuslim.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

public class ZadMediaService extends Service {
    static final String CH = "zad_media";
    static final int NID = 7101;
    static final String A_PLAY = "zad.PLAY", A_PAUSE = "zad.PAUSE", A_NEXT = "zad.NEXT",
            A_PREV = "zad.PREV", A_STOP = "zad.STOP";

    static ZadMediaService inst;
    static volatile boolean active = false;

    static String sTitle = "", sArtist = "";
    static boolean sPlaying, sPrev, sNext, sSeek;
    static long sPos, sDur;

    private MediaSessionCompat session;
    private boolean started;

    static void push(Context c, String title, String artist, boolean playing, double pos,
                     double dur, boolean prev, boolean next, boolean seek) {
        sTitle = title == null ? "" : title;
        sArtist = artist == null ? "" : artist;
        sPlaying = playing;
        sPos = (long) (pos * 1000);
        sDur = (long) (dur * 1000);
        sPrev = prev;
        sNext = next;
        sSeek = seek;
        try {
            if (inst != null) {
                inst.render();
            } else {
                ContextCompat.startForegroundService(c, new Intent(c, ZadMediaService.class));
            }
        } catch (Exception e) {
            // background-start restrictions: ignore, next update retries
        }
    }

    static void halt(Context c) {
        sTitle = "";
        active = false;
        try {
            c.stopService(new Intent(c, ZadMediaService.class));
        } catch (Exception e) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        session = new MediaSessionCompat(this, "ZadMedia");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { handle(A_PLAY, 0); }
            @Override public void onPause() { handle(A_PAUSE, 0); }
            @Override public void onSkipToNext() { handle(A_NEXT, 0); }
            @Override public void onSkipToPrevious() { handle(A_PREV, 0); }
            @Override public void onStop() { handle(A_STOP, 0); }
            @Override public void onSeekTo(long p) { handle("zad.SEEK", p / 1000.0); }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (sTitle.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (a != null) handle(a, 0); else render();
        return START_NOT_STICKY;
    }

    private void handle(String a, double pos) {
        String js;
        if (A_PLAY.equals(a)) { js = "play"; sPlaying = true; }
        else if (A_PAUSE.equals(a)) { js = "pause"; sPlaying = false; }
        else if (A_NEXT.equals(a)) js = "next";
        else if (A_PREV.equals(a)) js = "previous";
        else if (A_STOP.equals(a)) js = "stop";
        else { js = "seekto"; sPos = (long) (pos * 1000); }

        if (A_STOP.equals(a)) {
            ZadMediaPlugin.dispatch("stop", 0);
            sTitle = "";
            active = false;
            stopForeground(true);
            stopSelf();
            return;
        }
        ZadMediaPlugin.dispatch(js, pos);
        render();
    }

    private PendingIntent pi(String action, int code) {
        Intent i = new Intent(this, ZadMediaService.class).setAction(action);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void render() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel c = new NotificationChannel(CH, "تشغيل القرآن والأذكار",
                    NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(c);
        }

        session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, sTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "زاد المسلم")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, sDur)
                .build());
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP
                | (sPrev ? PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS : 0L)
                | (sNext ? PlaybackStateCompat.ACTION_SKIP_TO_NEXT : 0L)
                | (sSeek ? PlaybackStateCompat.ACTION_SEEK_TO : 0L);
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(sPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        sPos, sPlaying ? 1f : 0f)
                .build());
        session.setActive(true);

        int icon = getResources().getIdentifier("ic_stat_zad", "drawable", getPackageName());
        if (icon == 0) icon = android.R.drawable.ic_media_play;
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(icon)
                .setContentTitle(sTitle)
                .setContentText(sArtist)
                .setContentIntent(content)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(sPlaying);

        int idx = 0, prevIdx = -1, playIdx, nextIdx = -1;
        if (sPrev) {
            b.addAction(android.R.drawable.ic_media_previous, "السابق", pi(A_PREV, 1));
            prevIdx = idx++;
        }
        b.addAction(sPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                sPlaying ? "إيقاف مؤقت" : "تشغيل", pi(sPlaying ? A_PAUSE : A_PLAY, 2));
        playIdx = idx++;
        if (sNext) {
            b.addAction(android.R.drawable.ic_media_next, "التالي", pi(A_NEXT, 3));
            nextIdx = idx++;
        }
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "إغلاق", pi(A_STOP, 4));

        int[] compact;
        if (prevIdx >= 0 && nextIdx >= 0) compact = new int[]{prevIdx, playIdx, nextIdx};
        else if (nextIdx >= 0) compact = new int[]{playIdx, nextIdx};
        else if (prevIdx >= 0) compact = new int[]{prevIdx, playIdx};
        else compact = new int[]{playIdx};
        b.setStyle(new MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(compact));

        Notification n = b.build();
        try {
            if (sPlaying) {
                active = true;
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NID, n);
                }
                started = true;
            } else {
                active = false;
                if (!started) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    } else {
                        startForeground(NID, n);
                    }
                }
                stopForeground(false);
                started = false;
                nm.notify(NID, n);
            }
        } catch (Exception e) {
            nm.notify(NID, n);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        active = false;
        stopForeground(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        active = false;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(NID);
            session.setActive(false);
            session.release();
        } catch (Exception e) {
        }
        inst = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
