package com.zadalmuslim.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ZadMediaPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onPause() {
        super.onPause();
        // keep WebView audio/timers alive while the media notification is active
        if (ZadMediaService.active) {
            try {
                getBridge().getWebView().onResume();
                getBridge().getWebView().resumeTimers();
            } catch (Exception e) {
            }
        }
    }
}
