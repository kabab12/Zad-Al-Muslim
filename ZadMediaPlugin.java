package com.zadalmuslim.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "ZadMedia")
public class ZadMediaPlugin extends Plugin {
    static ZadMediaPlugin inst;

    @Override
    public void load() {
        inst = this;
    }

    @PluginMethod
    public void update(PluginCall call) {
        String title = call.getString("title", "");
        String artist = call.getString("artist", "");
        Boolean playing = call.getBoolean("playing", false);
        Double pos = call.getDouble("position", 0.0);
        Double dur = call.getDouble("duration", 0.0);
        Boolean prev = call.getBoolean("hasPrev", false);
        Boolean next = call.getBoolean("hasNext", false);
        Boolean seek = call.getBoolean("hasSeek", false);
        ZadMediaService.push(getContext(), title, artist, playing, pos, dur, prev, next, seek);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        ZadMediaService.halt(getContext());
        call.resolve();
    }

    static void dispatch(String action, double pos) {
        if (inst == null) return;
        JSObject o = new JSObject();
        o.put("action", action);
        o.put("position", pos);
        inst.notifyListeners("mediaAction", o);
    }
}
