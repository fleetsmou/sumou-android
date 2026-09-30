package com.sumou.fleet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SumouTrackerService extends Service {
    public static final String ACTION_START = "com.sumou.fleet.START";
    public static final String ACTION_STOP = "com.sumou.fleet.STOP";
    private static final String CHANNEL_ID = "sumou_tracking";
    private static final String PREFS = "sumou_native_tracker_v2";
    private static final int NOTIF_ID = 4711;
    public static volatile boolean isRunning = false;
    public static volatile String runningShiftId;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService http = Executors.newSingleThreadExecutor();
    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private PowerManager.WakeLock wakeLock;
    private Session session;
    private long generation;
    private boolean destroyed;
    // Only one HTTP call and one latest pending fix; outages cannot build a stale FIFO.
    private Request inFlight;
    private Location pending, lastAccepted;
    private long lastSuccessElapsed, nextAttemptElapsed;
    private int failures;
    private final Runnable retry = this::drain;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String terminalReason(Context context, String token, String shift) {
        SharedPreferences p = prefs(context);
        if (!TrackerPolicy.sameSession(token, shift, p.getString("deadToken", null), p.getString("deadShift", null))) return null;
        return p.getString("reason", null);
    }

    public static String stoppedShift(Context context) {
        return prefs(context).getString("deadShift", null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            String shift = intent.getStringExtra("shiftId"), token = intent.getStringExtra("token");
            if (session != null && ((shift != null && !shift.equals(session.shift)) || (token != null && !token.equals(session.token)))) return START_STICKY;
            stopTracking(null, false);
            return START_NOT_STICKY;
        }
        Session next = intent == null ? Session.restore(prefs(this)) : Session.from(intent);
        if (next == null || terminalReason(this, next.token, next.shift) != null) {
            if (session != null && isRunning) return START_STICKY;
            // A sticky restart without saved credentials must not leave a zombie GPS service.
            stopTracking(null, false);
            return START_NOT_STICKY;
        }
        if (session != null && session.same(next) && isRunning) return START_STICKY;
        releaseTracking();
        session = next;
        next.save(prefs(this));
        final long epoch = generation;
        try {
            startForeground(NOTIF_ID, buildNotification());
            isRunning = true;
            runningShiftId = next.shift;
            startTracking(next.interval, epoch);
        } catch (Exception e) {
            stopTracking(null, false);
            return START_NOT_STICKY;
        }
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
        } else b = new Notification.Builder(this);
        return b.setContentTitle("On duty — GPS active")
                .setContentText("Sumou Fleet is sharing your live location.")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build();
    }

    private void startTracking(int interval, final long epoch) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sumou:track");
            wakeLock.acquire();
        }
        fused = LocationServices.getFusedLocationProviderClient(this);
        // Keep the existing high accuracy request and moving-fix interval.
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setMinUpdateIntervalMillis(Math.max(3000, interval / 2))
                .setMinUpdateDistanceMeters(0f).build();
        callback = new LocationCallback() {
            @Override public void onLocationResult(@NonNull LocationResult result) {
                if (epoch != generation || session == null || destroyed) return;
                Location loc = result.getLastLocation();
                if (loc == null || !TrackerPolicy.validFix(loc.getLatitude(), loc.getLongitude(), accuracy(loc), loc.getTime())
                        || !TrackerPolicy.plausibleCapture(loc.getTime(), System.currentTimeMillis())) return;
                if (lastAccepted != null && !newer(loc, lastAccepted)) return;
                if (pending == null || newer(loc, pending)) pending = new Location(loc);
                drain();
            }
        };
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper()).addOnFailureListener(e -> {
            if (epoch == generation && !destroyed) stopTracking(null, false);
        });
    }

    private static float accuracy(Location loc) { return loc.hasAccuracy() ? loc.getAccuracy() : 999f; }
    private static float kmh(Location loc) { return loc.hasSpeed() ? Math.max(0f, loc.getSpeed() * 3.6f) : 0f; }
    private static boolean newer(Location next, Location previous) {
        if (next.getElapsedRealtimeNanos() > 0 && previous.getElapsedRealtimeNanos() > 0)
            return next.getElapsedRealtimeNanos() > previous.getElapsedRealtimeNanos();
        return next.getTime() > previous.getTime();
    }
    private static long ageMillis(Location loc) {
        if (loc.getElapsedRealtimeNanos() > 0)
            return Math.max(0L, (SystemClock.elapsedRealtimeNanos() - loc.getElapsedRealtimeNanos()) / 1000000L);
        return Math.max(0L, System.currentTimeMillis() - loc.getTime());
    }

    private void drain() {
        main.removeCallbacks(retry);
        if (destroyed || session == null || !isRunning || inFlight != null || pending == null) return;
        long now = SystemClock.elapsedRealtime();
        // Retry the latest captured point for at most two minutes; await a fresh fix after that.
        if (ageMillis(pending) > 120000L) { pending = null; return; }
        if (nextAttemptElapsed > now) { main.postDelayed(retry, nextAttemptElapsed - now); return; }
        if (lastAccepted != null && !newer(pending, lastAccepted)) { pending = null; return; }
        if (lastAccepted != null && TrackerPolicy.coalesceStationary(now - lastSuccessElapsed,
                lastAccepted.distanceTo(pending), kmh(lastAccepted), kmh(pending), accuracy(lastAccepted), accuracy(pending))) {
            main.postDelayed(retry, Math.max(1L, 30000L - (now - lastSuccessElapsed)));
            return;
        }
        Request request = new Request(generation, session, pending);
        pending = null;
        inFlight = request;
        http.execute(() -> send(request));
    }

    private void send(Request request) {
        int code = 0;
        String reason = null;
        try {
            if (request.cancelled) return;
            JSONObject p = new JSONObject();
            Location loc = request.location;
            p.put("lat", loc.getLatitude()); p.put("lng", loc.getLongitude());
            p.put("acc", Math.round(accuracy(loc)));
            p.put("speed", Math.min(250, Math.round(kmh(loc))));
            p.put("heading", loc.hasBearing() ? Math.round(loc.getBearing()) : JSONObject.NULL);
            // Location.getTime is capture time, not the time a queued HTTP call finally sends.
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            p.put("at", iso.format(new Date(loc.getTime())));
            SimpleDateFormat clock = new SimpleDateFormat("hh:mm a", Locale.US);
            clock.setTimeZone(TimeZone.getTimeZone("Asia/Kuwait"));
            p.put("time", clock.format(new Date(loc.getTime())).toLowerCase(Locale.US));
            JSONObject body = new JSONObject();
            body.put("p_token", request.session.token); body.put("p_shift", request.session.shift);
            body.put("p_point", true); body.put("p", p);
            HttpURLConnection c = (HttpURLConnection) new URL(request.session.url).openConnection();
            request.connection = c;
            if (request.cancelled) return;
            c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(15000);
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("apikey", request.session.apikey);
            c.setRequestProperty("Authorization", "Bearer " + request.session.apikey);
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = c.getOutputStream()) { os.write(out); }
            code = c.getResponseCode();
            String text = readBody(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) {
                try { reason = TrackerPolicy.terminalReason(new JSONObject(text).optString("message", "")); }
                catch (Exception ignored) { /* Non-JSON proxy failures use bounded retry. */ }
            }
        } catch (Exception ignored) {
            // Do not log RPC bodies: they contain the driver's private session token.
        } finally {
            if (request.connection != null) request.connection.disconnect();
            final int result = code;
            final String terminal = reason;
            main.post(() -> complete(request, result, terminal));
        }
    }

    private static String readBody(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int read;
            while (bytes.size() < 16384 && (read = input.read(buffer, 0, Math.min(buffer.length, 16384 - bytes.size()))) != -1) bytes.write(buffer, 0, read);
            return bytes.toString("UTF-8");
        }
    }

    private void complete(Request request, int code, String reason) {
        if (inFlight != request) return;
        inFlight = null;
        if (destroyed) return;
        // A late error from yesterday's shift must never stop today's session.
        if (request.cancelled || request.generation != generation || session == null || !session.same(request.session)) { drain(); return; }
        if (reason != null) { stopTracking(reason, true); return; }
        long now = SystemClock.elapsedRealtime();
        if (code >= 200 && code < 300) {
            lastAccepted = request.location; lastSuccessElapsed = now;
            failures = 0; nextAttemptElapsed = 0;
        } else {
            failures = Math.min(5, failures + 1);
            boolean retryable = code == 0 || code == 408 || code == 429 || code >= 500;
            nextAttemptElapsed = now + (retryable ? TrackerPolicy.backoffMillis(failures) : 60000L);
            if (retryable && (pending == null || newer(request.location, pending))) pending = request.location;
        }
        drain();
    }

    private void releaseTracking() {
        generation++;
        main.removeCallbacks(retry);
        try { if (fused != null && callback != null) fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
        callback = null;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
        if (inFlight != null) {
            inFlight.cancelled = true;
            if (inFlight.connection != null) inFlight.connection.disconnect();
        }
        pending = null; lastAccepted = null; failures = 0; nextAttemptElapsed = 0;
        session = null; isRunning = false; runningShiftId = null;
    }

    private void stopTracking(String reason, boolean terminal) {
        SharedPreferences.Editor edit = prefs(this).edit();
        if (terminal && session != null) edit.putString("reason", reason).putString("deadToken", session.token).putString("deadShift", session.shift);
        // commit before stopping so a killed process cannot restore known-dead credentials.
        edit.remove("url").remove("apikey").remove("token").remove("shift").remove("interval").commit();
        releaseTracking();
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override public void onDestroy() {
        destroyed = true;
        // Keep valid saved credentials for an OS sticky restart; explicit/terminal stops clear them.
        releaseTracking();
        http.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private static final class Request {
        final long generation;
        final Session session;
        final Location location;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        Request(long generation, Session session, Location location) {
            this.generation = generation; this.session = session; this.location = new Location(location);
        }
    }

    private static final class Session {
        final String url, apikey, token, shift;
        final int interval;
        Session(String url, String apikey, String token, String shift, int interval) {
            this.url = url; this.apikey = apikey; this.token = token; this.shift = shift;
            this.interval = Math.max(3000, interval);
        }
        static Session from(Intent i) {
            return create(i.getStringExtra("url"), i.getStringExtra("apikey"), i.getStringExtra("token"), i.getStringExtra("shiftId"), i.getIntExtra("interval", 10000));
        }
        static Session restore(SharedPreferences p) {
            return create(p.getString("url", null), p.getString("apikey", null), p.getString("token", null), p.getString("shift", null), p.getInt("interval", 10000));
        }
        static Session create(String url, String apikey, String token, String shift, int interval) {
            if (url == null || !url.startsWith("https://") || apikey == null || apikey.isEmpty() || token == null || token.isEmpty() || shift == null || shift.isEmpty()) return null;
            return new Session(url, apikey, token, shift, interval);
        }
        boolean same(Session other) { return other != null && TrackerPolicy.sameSession(token, shift, other.token, other.shift) && url.equals(other.url) && apikey.equals(other.apikey); }
        void save(SharedPreferences p) { p.edit().putString("url", url).putString("apikey", apikey).putString("token", token).putString("shift", shift).putInt("interval", interval).apply(); }
    }
}

// Pure policy kept in this file so the existing Android build copies it with the service.
final class TrackerPolicy {
    static boolean sameSession(String token, String shift, String otherToken, String otherShift) {
        return token != null && shift != null && token.equals(otherToken) && shift.equals(otherShift);
    }
    static boolean validFix(double lat, double lng, float accuracy, long capturedAt) {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
                && !(Math.abs(lat) < 0.001 && Math.abs(lng) < 0.001) && accuracy >= 0 && accuracy < 100000 && capturedAt > 0;
    }
    static boolean plausibleCapture(long capturedAt, long now) { return capturedAt > 0 && capturedAt <= now + 60000L; }
    static boolean coalesceStationary(long elapsed, float metres, float oldKmh, float newKmh, float oldAccuracy, float newAccuracy) {
        return elapsed < 30000L && metres < 3f && oldKmh <= 2f && newKmh <= 2f && newAccuracy >= oldAccuracy * 0.75f;
    }
    static long backoffMillis(int failures) {
        return Math.min(60000L, 5000L * (1L << Math.min(4, Math.max(0, failures - 1))));
    }
    static String terminalReason(String message) {
        if ("NO_ACTIVE_SHIFT".equals(message) || "SESSION_EXPIRED".equals(message)) return message;
        return null;
    }
}
