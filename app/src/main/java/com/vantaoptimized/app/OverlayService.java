package com.vantaoptimized.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Panel flotante con métricas en tiempo real, visible sobre otras apps. */
public class OverlayService extends Service {

    public static final String ACTION_STOP = "com.vantaoptimized.app.STOP";

    private static final String CHANNEL_ID = "vanta_monitor";
    private static final int NOTIFICATION_ID = 1;
    private static volatile boolean running = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService executor;
    private SystemSampler sampler;
    private WindowManager windowManager;
    private LinearLayout panel;
    private TextView appValue, fpsValue, cpuValue, gpuValue, ramValue, tempValue;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat();
        running = true;

        if (panel == null) {
            addOverlay();
        }
        if (executor == null) {
            sampler = new SystemSampler();
            executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    private void tick() {
        boolean granted = SystemSampler.hasPermission();
        SystemSampler.Metrics m = granted ? sampler.sample() : null;
        main.post(() -> render(m));
    }

    private void render(SystemSampler.Metrics m) {
        if (panel == null) {
            return;
        }
        if (m == null) {
            appValue.setText("Shizuku inactivo");
            return;
        }
        appValue.setText(m.focusPackage.isEmpty() ? "—" : m.focusPackage);
        fpsValue.setText(m.fps >= 0 ? String.valueOf(m.fps) : "--");
        cpuValue.setText(percent(m.cpuPercent));
        gpuValue.setText(percent(m.gpuPercent));
        ramValue.setText(m.ramTotalMb > 0 ? m.ramUsedMb + " / " + m.ramTotalMb + " MB" : "--");
        tempValue.setText(Float.isNaN(m.tempC) ? "--" : String.format(Locale.US, "%.1f °C", m.tempC));
    }

    private static String percent(int value) {
        return value >= 0 ? value + "%" : "--";
    }

    private void addOverlay() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xEE1A1A1A, 0xEE000000});
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x66FFFFFF);
        panel.setBackground(bg);

        appValue = text("—", 10, 0xFF9E9E9E, false);
        appValue.setMaxWidth(dp(160));
        appValue.setSingleLine(true);
        panel.addView(appValue);

        fpsValue = addRow("FPS");
        cpuValue = addRow("CPU");
        gpuValue = addRow("GPU");
        ramValue = addRow("RAM");
        tempValue = addRow("TEMP");

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(8);
        lp.y = dp(40);
        windowManager.addView(panel, lp);
    }

    private TextView addRow(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, 0);

        TextView l = text(label, 11, 0xFF9E9E9E, false);
        TextView v = text("--", 12, 0xFFFFFFFF, true);
        v.setGravity(Gravity.END);

        row.addView(l, new LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(v, new LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(row);
        return v;
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Monitor VantaOptimized", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("VantaOptimized activo")
                .setContentText("Métricas visibles sobre otras apps")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (panel != null && windowManager != null) {
            try {
                windowManager.removeView(panel);
            } catch (IllegalArgumentException ignored) {
                // La vista ya no estaba adjunta.
            }
            panel = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
