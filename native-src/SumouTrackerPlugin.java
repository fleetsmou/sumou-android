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
        String url = call.getString("url"), apikey = call.getString("apikey");
        String token = call.getString("token"), shiftId = call.getString("shiftId");
        if (url == null || !url.startsWith("https://") || apikey == null || apikey.isEmpty() || token == null || token.isEmpty() || shiftId == null || shiftId.isEmpty()) {
            call.reject("missing or invalid url/apikey/token/shiftId");
            return;
        }
        String terminal = SumouTrackerService.terminalReason(getContext(), token, shiftId);
        if (terminal != null) {
            JSObject ret = new JSObject();
            ret.put("running", false); ret.put("shiftId", shiftId); ret.put("reason", terminal);
            call.resolve(ret);
            return;
        }
        Intent i = new Intent(getContext(), SumouTrackerService.class);
        i.setAction(SumouTrackerService.ACTION_START);
        i.putExtra("url", url); i.putExtra("apikey", apikey); i.putExtra("token", token);
        i.putExtra("shiftId", shiftId); i.putExtra("interval", call.getInt("interval", 10000));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getContext().startForegroundService(i);
            else getContext().startService(i);
            call.resolve();
        } catch (Exception e) { call.reject("start failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent i = new Intent(getContext(), SumouTrackerService.class);
        i.setAction(SumouTrackerService.ACTION_STOP);
        // Optional session guard lets a delayed JS stop avoid cancelling a new shift.
        if (call.getString("token") != null) i.putExtra("token", call.getString("token"));
        if (call.getString("shiftId") != null) i.putExtra("shiftId", call.getString("shiftId"));
        try { getContext().startService(i); call.resolve(); }
        catch (Exception e) { call.reject("stop failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("running", SumouTrackerService.isRunning);
        String shift = SumouTrackerService.isRunning ? SumouTrackerService.runningShiftId : call.getString("shiftId");
        if (shift == null) shift = SumouTrackerService.stoppedShift(getContext());
        ret.put("shiftId", shift);
        // A terminal status only belongs to the exact token/shift the caller is checking.
        String reason = SumouTrackerService.terminalReason(getContext(), call.getString("token"), shift);
        if (reason != null) ret.put("reason", reason);
        call.resolve(ret);
    }
}
