package com.zadalmuslim.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

/**
 * الأذان كخدمة أمامية (Foreground Service): يُشغَّل صوت الأذان المضمّن بمجرى صوت المنبّه (USAGE_ALARM)
 * فلا يتأثر بوضع الصامت ولا بخفض صوت الإشعارات ولا بتقييدات الإشعارات في هواتف شاومي.
 * يبدأه AdhanScreenReceiver عند وقت الأذان (منبّه الساعة يسمح ببدء الخدمة من الخلفية).
 * الإشعار فيه زر «إيقاف الأذان»؛ وزر الصوت (داخل التطبيق أو عبر جلسة الوسائط) يوقفه أيضًا.
 */
public class AdhanService extends Service {
    static final String ACTION_STOP = "com.zadalmuslim.app.ADHAN_STOP";
    static final String CH = "zad_adhan_svc";
    static final int NID = 7421;
    private static final String[] NAMES = {"الفجر", "", "الظهر", "العصر", "المغرب", "العشاء"};

    /** هل الأذان يُشغَّل الآن من هذه الخدمة */
    static volatile boolean sPlaying = false;

    private MediaPlayer mp;
    private MediaSession ms;
    private AudioManager am;
    private AudioFocusRequest afr;
    private AudioManager.OnAudioFocusChangeListener afl;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable failsafe = new Runnable() {
        @Override
        public void run() {
            stopSelf();
        }
    };

    static void start(Context c, int idx, long ms, String m, float vol, boolean scr) {
        Intent i = new Intent(c, AdhanService.class)
                .putExtra("idx", idx).putExtra("ms", ms).putExtra("m", m)
                .putExtra("vol", vol).putExtra("scr", scr);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, AdhanService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null && ACTION_STOP.equals(in.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int idx = in == null ? -1 : in.getIntExtra("idx", -1);
        long ms0 = in == null ? 0L : in.getLongExtra("ms", 0L);
        String m = in == null ? "noreen" : in.getStringExtra("m");
        float vol = in == null ? 1f : in.getFloatExtra("vol", 1f);
        boolean scr = in == null || in.getBooleanExtra("scr", true);
        if (m == null || m.length() == 0) m = "noreen";
        try {
            Notification n = buildNotification(idx, ms0, scr);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NID, n);
            }
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
        play(m, vol);
        return START_NOT_STICKY;
    }

    private Notification buildNotification(int idx, long ms0, boolean scr) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "الأذان (تشغيل الصوت)", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("يظهر أثناء رفع الأذان، وفيه زر إيقاف الأذان");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        int small = getResources().getIdentifier("ic_stat_zad", "drawable", getPackageName());
        if (small == 0) small = android.R.drawable.ic_lock_idle_alarm;
        String name = idx >= 0 && idx < NAMES.length ? NAMES[idx] : "";
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent open = new Intent(this, MainActivity.class)
                .setAction("com.zadalmuslim.app.OPEN_ADHAN")
                .putExtra(AdhanScreen.EXTRA_IDX, idx)
                .putExtra("ms", ms0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 7410 + (idx < 0 ? 9 : idx), open, fl);

        Intent st = new Intent(this, AdhanService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 7422, st, fl);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(small)
                .setContentTitle(name.length() > 0 ? "حان الآن أذان " + name : "حان الآن وقت الأذان")
                .setContentText("حيّ على الصلاة")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(0, "⏹ إيقاف الأذان", stopPi);
        if (scr) b.setFullScreenIntent(openPi, true);
        return b.build();
    }

    private void play(String m, float vol) {
        releasePlayer();
        try {
            int res = getResources().getIdentifier("adhan_" + m, "raw", getPackageName());
            if (res == 0) res = getResources().getIdentifier("adhan_noreen", "raw", getPackageName());
            if (res == 0) {
                stopSelf();
                return;
            }
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            requestFocus(attrs);
            mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setWakeMode(getApplicationContext(), android.os.PowerManager.PARTIAL_WAKE_LOCK);
            AssetFileDescriptor afd = getResources().openRawResourceFd(res);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            float v = Math.max(0f, Math.min(1f, vol));
            mp.setVolume(v, v);
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer p) {
                    stopSelf();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer p, int what, int extra) {
                    stopSelf();
                    return true;
                }
            });
            mp.prepare();
            mp.start();
            sPlaying = true;
            h.removeCallbacks(failsafe);
            h.postDelayed(failsafe, 8 * 60 * 1000L);
            startVolumeSession();
        } catch (Throwable t) {
            stopSelf();
        }
    }

    /** جلسة وسائط تلتقط أزرار الصوت (حين لا تكون شاشة التطبيق ظاهرة) فتوقف الأذان بدل تغيير الصوت */
    private void startVolumeSession() {
        try {
            ms = new MediaSession(this, "zad_adhan");
            ms.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build());
            ms.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                @Override
                public void onAdjustVolume(int direction) {
                    MainActivity.sSilencedAt = System.currentTimeMillis();
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            stopSelf();
                        }
                    });
                }
            });
            ms.setActive(true);
        } catch (Throwable ignored) {
        }
    }

    private void requestFocus(AudioAttributes attrs) {
        try {
            am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            afl = new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    // لا نوقف الأذان إن أخذ تطبيق آخر التركيز؛ الإيقاف بيد المستخدم فقط
                }
            };
            if (Build.VERSION.SDK_INT >= 26) {
                afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(afl)
                        .build();
                am.requestAudioFocus(afr);
            } else {
                am.requestAudioFocus(afl, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Throwable ignored) {
        }
    }

    private void releasePlayer() {
        try {
            if (mp != null) {
                try {
                    mp.stop();
                } catch (Throwable ignored) {
                }
                mp.release();
            }
        } catch (Throwable ignored) {
        }
        mp = null;
        try {
            if (ms != null) {
                ms.setActive(false);
                ms.release();
            }
        } catch (Throwable ignored) {
        }
        ms = null;
        try {
            if (am != null) {
                if (Build.VERSION.SDK_INT >= 26 && afr != null) am.abandonAudioFocusRequest(afr);
                else if (afl != null) am.abandonAudioFocus(afl);
            }
        } catch (Throwable ignored) {
        }
        afr = null;
        afl = null;
    }

    @Override
    public void onDestroy() {
        sPlaying = false;
        h.removeCallbacks(failsafe);
        releasePlayer();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
