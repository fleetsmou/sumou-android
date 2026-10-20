package com.sumou.fleet;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(name = "SumouTracker", permissions = {
        @Permission(alias = "location", strings = {
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        }),
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
})
public class SumouTrackerPlugin extends Plugin {
    private final Handler main = new Handler(Looper.getMainLooper());

    @PluginMethod
    public void start(PluginCall call) {
        String url = call.getString("url"), apikey = call.getString("apikey");
        String token = call.getString("token"), shiftId = call.getString("shiftId");
        if (url == null || !url.startsWith("https://") || apikey == null || apikey.isEmpty()
                || token == null || token.isEmpty() || shiftId == null || shiftId.isEmpty()) {
            call.reject("missing or invalid url/apikey/token/shiftId");
            return;
        }
        String terminal = SumouTrackerService.terminalReason(getContext(), token, shiftId);
        if (terminal != null) { resolveStatus(call, false, terminal); return; }
        // A recreated WebView can reconnect after the Activity has gone off screen.
        // Adopt its exact live session without killing/relaunching a healthy FGS or
        // requesting new permissions from a now-background Activity.
        if (SumouTrackerService.isRunningFor(getContext(), token, shiftId)) {
            resolveStatus(call, true, null);
            return;
        }
        ensurePermissions(call);
    }

    @PluginMethod
    public void preparePermissions(PluginCall call) {
        // Clock in calls this before saving a shift. Asking permissions here cannot
        // accidentally start location sharing while the driver is still off duty.
        ensurePermissions(call);
    }

    private void ensurePermissions(PluginCall call) {
        // Request from the visible Clock in/resume flow, before launching the service.
        // A running location FGS retains while-in-use access after Home/lock/swipe;
        // this flow does not need ACCESS_BACKGROUND_LOCATION.
        if (!hasLocationPermission()) {
            requestPermissionForAlias("location", call, "locationPermissionResult");
            return;
        }
        requestNotificationsThenContinue(call);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @PermissionCallback
    private void locationPermissionResult(PluginCall call) {
        if (call == null) return;
        if (!hasLocationPermission()) { resolveStatus(call, false, "LOCATION_PERMISSION_DENIED"); return; }
        requestNotificationsThenContinue(call);
    }

    private void requestNotificationsThenContinue(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notificationPermissionResult");
            return;
        }
        completePermissionFlow(call);
    }

    @PermissionCallback
    private void notificationPermissionResult(PluginCall call) {
        // Android allows the location FGS when notifications are denied. Expose the
        // status so the app can explain how to enable the pinned notification.
        if (call != null) completePermissionFlow(call);
    }

    private void resolveStatus(PluginCall call, boolean running, String reason) {
        JSObject ret = new JSObject();
        boolean preparing = "preparePermissions".equals(call.getMethodName());
        ret.put("running", !preparing && running);
        if (preparing) ret.put("ready", running);
        ret.put("shiftId", call.getString("shiftId"));
        ret.put("notificationsEnabled", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
        ret.put("preciseLocation", ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED);
        if (reason != null) ret.put("reason", reason);
        call.resolve(ret);
    }

    private void completePermissionFlow(PluginCall call) {
        LocationManager locations = (LocationManager) getContext().getSystemService(android.content.Context.LOCATION_SERVICE);
        if (locations == null || !LocationManagerCompat.isLocationEnabled(locations)) {
            resolveStatus(call, false, "LOCATION_DISABLED");
            return;
        }
        if ("preparePermissions".equals(call.getMethodName())) {
            resolveStatus(call, true, null);
            return;
        }
        startService(call);
    }

    private void startService(PluginCall call) {
        final String shiftId = call.getString("shiftId"), token = call.getString("token");
        Intent i = new Intent(getContext(), SumouTrackerService.class);
        i.setAction(SumouTrackerService.ACTION_START);
        i.putExtra("url", call.getString("url")); i.putExtra("apikey", call.getString("apikey"));
        i.putExtra("token", token); i.putExtra("shiftId", shiftId);
        i.putExtra("interval", call.getInt("interval", 10000));
        AtomicBoolean answered = new AtomicBoolean(false);
        Runnable timeout = () -> {
            if (!answered.compareAndSet(false, true)) return;
            // Do not let a late subscription silently run alongside the web fallback.
            stopMatchingSession(token, shiftId);
            resolveStatus(call, false, "LOCATION_START_TIMEOUT");
        };
        i.putExtra("receiver", new ResultReceiver(main) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (!answered.compareAndSet(false, true)) return;
                main.removeCallbacks(timeout);
                resolveStatus(call, data.getBoolean("running", false), data.getString("reason"));
            }
        });
        try {
            main.postDelayed(timeout, 15000L);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getContext().startForegroundService(i);
            else getContext().startService(i);
            // Resolve only when FusedLocationProvider confirms the native subscription.
        } catch (Exception e) {
            if (answered.compareAndSet(false, true)) {
                main.removeCallbacks(timeout);
                resolveStatus(call, false, "LOCATION_START_FAILED");
            }
        }
    }

    private void stopMatchingSession(String token, String shiftId) {
        if (SumouTrackerService.clearSavedSession(getContext(), token, shiftId))
            getContext().stopService(new Intent(getContext(), SumouTrackerService.class));
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            // Clear saved credentials even when no service is alive; do not create a
            // background service just to stop it. Guards protect a newer shift.
            stopMatchingSession(call.getString("token"), call.getString("shiftId"));
            resolveStatus(call, false, null);
        } catch (Exception e) { call.reject("stop failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject ret = new JSObject();
        String requested = call.getString("shiftId");
        boolean running = SumouTrackerService.isRunning
                && (requested == null || SumouTrackerService.isRunningFor(getContext(), call.getString("token"), requested));
        ret.put("running", running);
        ret.put("starting", requested != null && requested.equals(SumouTrackerService.startingShiftId));
        String shift = running ? SumouTrackerService.runningShiftId : requested;
        if (shift == null) shift = SumouTrackerService.stoppedShift(getContext());
        ret.put("shiftId", shift);
        ret.put("notificationsEnabled", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
        String reason = SumouTrackerService.statusReason(getContext(), call.getString("token"), shift);
        if (reason != null) ret.put("reason", reason);
        call.resolve(ret);
    }
}
