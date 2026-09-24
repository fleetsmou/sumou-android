package com.sumou.fleet;

import android.content.Intent;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "SumouTracker")
public class SumouTrackerPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        String url = call.getString("url");
        String token = call.getString("token");
        String shiftId = call.getString("shiftId");
        if (url == null || token == null || shiftId == null) {
            call.reject("missing url/token/shiftId");
            return;
        }
        Intent i = new Intent(getContext(), SumouTrackerService.class);
        i.setAction(SumouTrackerService.ACTION_START);
        i.putExtra("url", url);
        i.putExtra("apikey", call.getString("apikey"));
        i.putExtra("token", token);
        i.putExtra("shiftId", shiftId);
        i.putExtra("interval", call.getInt("interval", 10000));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(i);
            } else {
                getContext().startService(i);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("start failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent i = new Intent(getContext(), SumouTrackerService.class);
        i.setAction(SumouTrackerService.ACTION_STOP);
        try { getContext().startService(i); } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("running", SumouTrackerService.isRunning);
        call.resolve(ret);
    }
}
