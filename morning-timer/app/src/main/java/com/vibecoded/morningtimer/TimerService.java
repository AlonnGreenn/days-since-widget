package com.vibecoded.morningtimer;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Runs the stopwatch: floating window, ongoing notification, and the beeps. */
public class TimerService extends Service {
    private static final String ACTION_DONE = "done", CHANNEL = "timer";
    private static final long MAX_MS = 4 * 60 * 60_000L; // safety stop after 4 h

    public static boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store store;
    private Profile profile;
    private long startMs;
    private List<Long> beeps;
    private int nextBeep;
    private PowerManager.WakeLock wakeLock;

    private WindowManager wm;
    private LinearLayout overlay;
    private WindowManager.LayoutParams lp;
    private TextView timeView;

    public static void start(Context ctx, int profileId, long startMs) {
        Intent i = new Intent(ctx, TimerService.class)
                .putExtra(Scheduler.EXTRA_ID, profileId).putExtra(Scheduler.EXTRA_START, startMs);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DONE.equals(intent.getAction())) { finish(); return START_NOT_STICKY; }
        if (intent == null) return START_NOT_STICKY;
        if (running) { startInForeground(); return START_NOT_STICKY; } // already timing: keep the current run
        store = new Store(this);
        profile = store.find(intent.getIntExtra(Scheduler.EXTRA_ID, -1));
        if (profile == null) profile = new Profile();
        startMs = intent.getLongExtra(Scheduler.EXTRA_START, System.currentTimeMillis());
        beeps = profile.beepTimesMs(MAX_MS);
        long elapsed = System.currentTimeMillis() - startMs;
        nextBeep = 0;
        while (nextBeep < beeps.size() && beeps.get(nextBeep) <= elapsed) nextBeep++;

        startInForeground();
        running = true;
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "morningtimer:run");
        wakeLock.acquire(MAX_MS);
        if (Settings.canDrawOverlays(this)) showOverlay();
        handler.post(tick);
        return START_NOT_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long elapsed = System.currentTimeMillis() - startMs;
            if (elapsed > MAX_MS) { finish(); return; }
            if (timeView != null) timeView.setText(format(elapsed));
            if (nextBeep < beeps.size() && elapsed >= beeps.get(nextBeep)) {
                while (nextBeep < beeps.size() && beeps.get(nextBeep) <= elapsed) nextBeep++;
                Beeper.play(store.sound(), store.volume());
            }
            handler.postDelayed(this, 1000 - (elapsed % 1000) + 5);
        }
    };

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Running timer", NotificationManager.IMPORTANCE_LOW));
        PendingIntent done = PendingIntent.getService(this, 0,
                new Intent(this, TimerService.class).setAction(ACTION_DONE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(profile.name)
                .setWhen(startMs).setUsesChronometer(true).setShowWhen(true)
                .setOngoing(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Done", done).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    // ---------- floating window ----------

    @SuppressLint("ClickableViewAccessibility")
    private void showOverlay() {
        wm = getSystemService(WindowManager.class);
        float scale = store.size() / 100f;
        float dp = getResources().getDisplayMetrics().density * scale;

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding((int) (12 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(store.opacity() * 255 / 100, 20, 20, 24));
        bg.setCornerRadius(20 * dp);
        overlay.setBackground(bg);

        timeView = new TextView(this);
        timeView.setTextColor(Color.WHITE);
        timeView.setTextSize(18 * scale);
        timeView.setTypeface(android.graphics.Typeface.MONOSPACE);
        timeView.setText(format(System.currentTimeMillis() - startMs));
        overlay.addView(timeView);

        Button done = new Button(this);
        done.setText("Done");
        done.setAllCaps(false);
        done.setTextSize(13 * scale);
        done.setTextColor(Color.WHITE);
        done.setMinWidth(0); done.setMinimumWidth(0);
        done.setMinHeight(0); done.setMinimumHeight(0);
        done.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));
        GradientDrawable dbg = new GradientDrawable();
        dbg.setColor(Color.rgb(232, 131, 58));
        dbg.setCornerRadius(14 * dp);
        done.setBackground(dbg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = (int) (10 * dp);
        overlay.addView(done, blp);
        done.setOnClickListener(v -> finish());

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -10_000; // off-screen until measured, then snapped
        wm.addView(overlay, lp);
        overlay.post(() -> snapTo(store.position()));

        // Drag anywhere except the button; on release hop to the nearest of the 8 spots.
        View.OnTouchListener drag = new View.OnTouchListener() {
            float downX, downY; int startX, startY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); startX = lp.x; startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - downX);
                        lp.y = startY + (int) (e.getRawY() - downY);
                        wm.updateViewLayout(overlay, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        int p = nearestSpot();
                        store.setPosition(p);
                        snapTo(p);
                        return true;
                }
                return false;
            }
        };
        overlay.setOnTouchListener(drag);
        timeView.setOnTouchListener(drag);
    }

    /** Spots: 0 top-left, 1 top-right, 2/3 left/right at 1/3 height, 4/5 at 2/3, 6 bottom-left, 7 bottom-right. */
    private int[] spot(int i) {
        Rect s = screen();
        float d = getResources().getDisplayMetrics().density;
        int w = overlay.getWidth(), h = overlay.getHeight();
        int margin = (int) (8 * d), top = (int) (40 * d), bottom = (int) (56 * d);
        int x = (i % 2 == 0) ? margin : s.width() - w - margin;
        int usable = s.height() - h - top - bottom;
        int row = i / 2; // 0..3
        int y = top + usable * row / 3;
        return new int[]{x, y};
    }

    private int nearestSpot() {
        int best = 0; long bestD = Long.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            int[] p = spot(i);
            long dx = p[0] - lp.x, dy = p[1] - lp.y, dd = dx * dx + dy * dy;
            if (dd < bestD) { bestD = dd; best = i; }
        }
        return best;
    }

    private void snapTo(int i) {
        if (overlay == null) return;
        int[] p = spot(i);
        lp.x = p[0]; lp.y = p[1];
        wm.updateViewLayout(overlay, lp);
    }

    private Rect screen() {
        if (Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds();
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new Rect(0, 0, m.widthPixels, m.heightPixels);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (overlay != null) overlay.post(() -> snapTo(store.position()));
    }

    // ---------- stop ----------

    private void finish() {
        if (running && profile != null) {
            String day = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new Date(startMs));
            store.setLastResult(profile.name + " — " + day + " → " + format(System.currentTimeMillis() - startMs));
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (overlay != null) { wm.removeView(overlay); overlay = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    static String format(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
