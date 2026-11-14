package com.zadalmuslim.app;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;

/**
 * Stops the adhan:
 *  1) with the volume buttons (up or down)
 *  2) by pulling down the notification shade, even slightly
 */
public class MainActivity extends BridgeActivity {

    private volatile boolean adhanPlaying = false;
    private boolean swallowVolume = false;
    private boolean statusBarVisible = true;

    /** Called by the app page when the adhan starts and ends */
    private class AdhanBridge {
        @JavascriptInterface
        public void setPlaying(boolean on) {
            adhanPlaying = on;
        }
    }

    /** Rotate the screen inside the Mushaf even if auto-rotate is off in the phone */
    private class OrientBridge {
        @JavascriptInterface
        public void setSensor(boolean on) {
            runOnUiThread(() -> setRequestedOrientation(on
                    ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        }
    }

    /**
     * Background recitation: the page tells us when audio starts and stops.
     * While audio plays we hold a partial wake lock and keep the WebView alive
     * after leaving the app; between two ayahs (a brief stop) we wait 8 seconds
     * before releasing so the audio does not cut out.
     */
    private volatile boolean audioOn = false;
    private PowerManager.WakeLock audioLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable audioRelax = () -> {
        audioOn = false;
        releaseAudioLock();
    };

    private class AudioBridge {
        @JavascriptInterface
        public void setPlaying(boolean on) {
            runOnUiThread(() -> {
                uiHandler.removeCallbacks(audioRelax);
                if (on) {
                    audioOn = true;
                    acquireAudioLock();
                } else {
                    uiHandler.postDelayed(audioRelax, 8000);
                }
            });
        }
    }

    private void acquireAudioLock() {
        try {
            if (audioLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                audioLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zad:audio");
                audioLock.setReferenceCounted(false);
            }
            if (!audioLock.isHeld()) audioLock.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception ignored) { }
        try {
            if (wifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zad:wifi");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) { }
    }

    private void releaseAudioLock() {
        try {
            if (audioLock != null && audioLock.isHeld()) audioLock.release();
        } catch (Exception ignored) { }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) { }
    }

    private void keepWebAlive() {
        if (!audioOn) return;
        try {
            getBridge().getWebView().onResume();
            getBridge().getWebView().resumeTimers();
        } catch (Exception ignored) { }
    }

    @Override
    public void onPause() {
        super.onPause();
        keepWebAlive();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepWebAlive();
    }

    @Override
    public void onDestroy() {
        uiHandler.removeCallbacks(audioRelax);
        releaseAudioLock();
        super.onDestroy();
    }

    /** Flag telling the page that the app itself requests permissions at launch */
    private class PermBridge {
        @JavascriptInterface
        public boolean native1() { return true; }
    }

    private static final int REQ_FIRST_RUN = 7001;

    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * On every app launch: ask for notifications and location if missing
     * (official system dialog). If already granted nothing is shown, and after
     * the user denies twice the system stops showing the dialog.
     * Then open the exact-alarm settings screen only once, if needed for
     * adhan times.
     */
    private void askFirstRunPermissions() {
        try {
            ArrayList<String> need = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS))
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION))
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (!has(Manifest.permission.ACCESS_COARSE_LOCATION))
                need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (need.isEmpty()) {
                askExactAlarm();
            } else {
                ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_FIRST_RUN);
            }
        } catch (Exception ignored) { }
    }

    private void askExactAlarm() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                SharedPreferences sp = getSharedPreferences("zad_perm", MODE_PRIVATE);
                if (sp.getBoolean("exact_asked", false)) return;
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (am != null && !am.canScheduleExactAlarms()) {
                    sp.edit().putBoolean("exact_asked", true).apply();
                    Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_FIRST_RUN) return;
        askExactAlarm();
        // Tell the page to refresh permission state and schedule notifications
        uiHandler.postDelayed(() -> {
            try {
                getBridge().getWebView().evaluateJavascript(
                        "document.dispatchEvent(new Event('visibilitychange'))", null);
            } catch (Exception ignored) { }
        }, 1500);
    }

    private void stopAdhan() {
        if (!adhanPlaying) return;
        adhanPlaying = false;
        runOnUiThread(() -> {
            try {
                getBridge().getWebView().evaluateJavascript(
                        "window.adStopNative&&window.adStopNative()", null);
            } catch (Exception ignored) { }
        });
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getBridge().getWebView().addJavascriptInterface(new AdhanBridge(), "AdhanNative");
            getBridge().getWebView().addJavascriptInterface(new OrientBridge(), "OrientNative");
            getBridge().getWebView().addJavascriptInterface(new AudioBridge(), "ZadAudioNative");
            getBridge().getWebView().addJavascriptInterface(new PermBridge(), "ZadPerm");
        } catch (Exception ignored) { }

        // Ask for permissions at launch
        uiHandler.postDelayed(this::askFirstRunPermissions, 800);

        // Small service: when the app is removed from recent apps it ends the
        // process, so audio stops and the shade player disappears
        try {
            startService(new Intent(this, ZadTaskService.class));
        } catch (Exception ignored) { }

        // Status bar appearing (first swipe in fullscreen mode) = stop the adhan
        final View decor = getWindow().getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) -> {
            boolean vis = insets.isVisible(WindowInsetsCompat.Type.statusBars());
            if (vis && !statusBarVisible) stopAdhan();
            statusBarVisible = vis;
            return ViewCompat.onApplyWindowInsets(v, insets);
        });
        if (Build.VERSION.SDK_INT < 30) {
            decor.setOnSystemUiVisibilityChangeListener(flags -> {
                if ((flags & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) stopAdhan();
            });
        }
    }

    // Pulling the shade (normal mode) makes the window lose focus
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) stopAdhan();
    }

    // Volume buttons
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && adhanPlaying) {
                swallowVolume = true;
                stopAdhan();
                return true;
            }
            if (swallowVolume) {
                if (event.getAction() == KeyEvent.ACTION_UP) swallowVolume = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
