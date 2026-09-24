package com.sumou.fleet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SumouTrackerService extends Service {
    public static final String ACTION_START = "com.sumou.fleet.START";
    public static final String ACTION_STOP = "com.sumou.fleet.STOP";
    private static final String CHANNEL_ID = "sumou_tracking";
    private static final int NOTIF_ID = 4711;
    public static volatile boolean isRunning = false;

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private PowerManager.WakeLock wakeLock;
    private final ExecutorService http = Executors.newSingleThreadExecutor();

    private String url, apikey, token, shiftId;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTracking();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            url = intent.getStringExtra("url");
            apikey = intent.getStringExtra("apikey");
            token = intent.getStringExtra("token");
            shiftId = intent.getStringExtra("shiftId");
        }
        startForeground(NOTIF_ID, buildNotification());
        int interval = intent != null ? intent.getIntExtra("interval", 10000) : 10000;
        startTracking(interval);
        isRunning = true;
        return START_STICKY;
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "On duty tracking", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("On duty — GPS active");
        b.setContentText("Sumou Fleet is sharing your live location.");
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation);
        b.setOngoing(true);
        return b.build();
    }

    private void startTracking(int interval) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sumou:track");
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}

        fused = LocationServices.getFusedLocationProviderClient(this);
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setMinUpdateIntervalMillis(Math.max(3000, interval / 2))
                .setMinUpdateDistanceMeters(0f)
                .build();
        callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null) post(loc);
            }
        };
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException ignored) {}
    }

    private void post(final Location loc) {
        final double lat = loc.getLatitude();
        final double lng = loc.getLongitude();
        final float acc = loc.getAccuracy();
        final float spd = loc.hasSpeed() ? loc.getSpeed() : 0f;
        final float bearing = loc.hasBearing() ? loc.getBearing() : 0f;
        if (url == null || token == null || shiftId == null) return;
        http.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    JSONObject p = new JSONObject();
                    p.put("lat", lat);
                    p.put("lng", lng);
                    p.put("acc", Math.round(acc));
                    p.put("speed", Math.round(spd * 3.6));
                    p.put("heading", Math.round(bearing));
                    p.put("time", new SimpleDateFormat("hh:mm a", Locale.US).format(new Date()).toLowerCase(Locale.US));
                    JSONObject body = new JSONObject();
                    body.put("p_token", token);
                    body.put("p_shift", shiftId);
                    body.put("p_point", true);
                    body.put("p", p);

                    URL u = new URL(url);
                    c = (HttpURLConnection) u.openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(15000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    if (apikey != null) {
                        c.setRequestProperty("apikey", apikey);
                        c.setRequestProperty("Authorization", "Bearer " + apikey);
                    }
                    byte[] out = body.toString().getBytes("UTF-8");
                    OutputStream os = c.getOutputStream();
                    os.write(out);
                    os.close();
                    int code = c.getResponseCode();
                    try { c.getInputStream().close(); } catch (Exception e) {
                        if (c.getErrorStream() != null) c.getErrorStream().close();
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    private void stopTracking() {
        try { if (fused != null && callback != null) fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        isRunning = false;
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopTracking();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
