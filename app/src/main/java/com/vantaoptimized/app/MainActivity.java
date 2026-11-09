package com.vantaoptimized.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final int BLACK = 0xFF000000;
    private static final int DARK = 0xFF121212;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFF9E9E9E;
    private static final int LIGHT_GRAY = 0xFFD0D0D0;
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final float FPS_SCALE = 120f;

    private final SystemSampler sampler = new SystemSampler();
    private ScheduledExecutorService executor;

    private GaugeView fpsGauge;
    private GaugeView cpuGauge;
    private GaugeView ramGauge;
    private TextView cores;
    private TextView ramDetail;
    private TextView cpuTemp;
    private TextView batteryLevel;
    private TextView batteryTemp;
    private TextView shizukuStatus;
    private TextView overlayStatus;
    private TextView monitorStatus;
    private Button shizukuButton;
    private Button overlayButton;
    private Button monitorButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleAtFixedRate(this::poll, 0, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Corre en segundo plano: consulta Shizuku y luego pinta en la interfaz. */
    private void poll() {
        boolean running = SystemSampler.isServiceRunning();
        boolean granted = running && SystemSampler.hasPermission();
        SystemSampler.Metrics m = granted ? sampler.sample() : null;
        runOnUiThread(() -> render(m, running, granted));
    }

    private void render(SystemSampler.Metrics m, boolean running, boolean granted) {
        boolean canDraw = Settings.canDrawOverlays(this);
        boolean monitorOn = OverlayService.isRunning();

        shizukuStatus.setText(!isShizukuInstalled() ? "No instalado"
                : !running ? "No está en ejecución" : granted ? "Listo" : "Falta permiso");
        boolean installed = isShizukuInstalled();
        shizukuButton.setText(!installed ? "Instalar Shizuku"
                : !running ? "Abrir Shizuku"
                : granted ? "Permiso concedido" : "Conceder permiso");
        shizukuButton.setEnabled(!granted);

        overlayStatus.setText(canDraw ? "Permitido" : "Pendiente");
        overlayButton.setText(canDraw ? "Permiso concedido" : "Permitir superposición");
        overlayButton.setEnabled(!canDraw);

        monitorStatus.setText(monitorOn ? "Activo" : "Detenido");
        monitorButton.setText(monitorOn ? "Detener monitor" : "Iniciar monitor flotante");

        if (m != null) {
            fpsGauge.update(m.fps >= 0 ? m.fps / FPS_SCALE : 0f,
                    m.fps >= 0 ? String.valueOf(m.fps) : "--", "FPS");
            cpuGauge.update(m.cpuPercent >= 0 ? m.cpuPercent / 100f : 0f,
                    m.cpuPercent >= 0 ? m.cpuPercent + "%" : "--", "CPU");
            float ram = m.ramTotalMb > 0 ? (float) m.ramUsedMb / m.ramTotalMb : 0f;
            ramGauge.update(ram, Math.round(ram * 100) + "%", "RAM");
            ramDetail.setText(m.ramTotalMb > 0 ? m.ramUsedMb + " / " + m.ramTotalMb + " MB" : "--");
            cpuTemp.setText(Float.isNaN(m.tempC) ? "--" : String.format(Locale.US, "%.1f °C", m.tempC));
        } else {
            fpsGauge.update(0f, "--", "FPS");
            cpuGauge.update(0f, "--", "CPU");
            ramGauge.update(0f, "--", "RAM");
            ramDetail.setText("--");
            cpuTemp.setText("--");
        }

        cores.setText(String.valueOf(Runtime.getRuntime().availableProcessors()));

        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            batteryLevel.setText(level >= 0 && scale > 0 ? (level * 100 / scale) + "%" : "--");
            batteryTemp.setText(temp != Integer.MIN_VALUE
                    ? String.format(Locale.US, "%.1f °C", temp / 10f) : "--");
        }
    }

    // ---------- Acciones ----------

    private void onShizukuClick() {
        if (!isShizukuInstalled()) {
            openShizukuStore();
        } else if (!SystemSampler.isServiceRunning()) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (launch != null) {
                startActivity(launch);
            }
        } else {
            SystemSampler.requestPermission();
        }
    }

    private boolean isShizukuInstalled() {
        return getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null;
    }

    /** Abre la ficha de Shizuku en la Play Store; el usuario confirma la instalación. */
    private void openShizukuStore() {
        Intent market = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + SHIZUKU_PACKAGE));
        try {
            startActivity(market);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + SHIZUKU_PACKAGE)));
        }
    }

    private void onOverlayClick() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void onMonitorClick() {
        Intent service = new Intent(this, OverlayService.class);
        if (OverlayService.isRunning()) {
            service.setAction(OverlayService.ACTION_STOP);
            startService(service);
        } else if (!Settings.canDrawOverlays(this)) {
            toast("Primero concede el permiso de superposición");
        } else if (!SystemSampler.hasPermission()) {
            toast("Primero concede el permiso en Shizuku");
        } else {
            startForegroundService(service);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ---------- Interfaz ----------

    private View buildUi() {
        LinearLayout root = column();
        root.setPadding(dp(20), dp(48), dp(20), dp(32));

        root.addView(text("VANTA", 34, WHITE, true));
        root.addView(text("OPTIMIZED · monitor del sistema", 13, GRAY, false));

        LinearLayout gauges = new LinearLayout(this);
        gauges.setOrientation(LinearLayout.HORIZONTAL);
        gauges.setGravity(Gravity.CENTER);
        fpsGauge = new GaugeView(this);
        cpuGauge = new GaugeView(this);
        ramGauge = new GaugeView(this);
        gauges.addView(fpsGauge, new LinearLayout.LayoutParams(0, dp(140), 1f));
        gauges.addView(cpuGauge, new LinearLayout.LayoutParams(0, dp(140), 1f));
        gauges.addView(ramGauge, new LinearLayout.LayoutParams(0, dp(140), 1f));
        root.addView(gauges, blockParams(24));

        LinearLayout shizukuCard = card("SHIZUKU");
        shizukuStatus = addLine(shizukuCard, "Estado");
        shizukuButton = pillButton("Abrir Shizuku");
        shizukuButton.setOnClickListener(v -> onShizukuClick());
        shizukuCard.addView(shizukuButton, blockParams(14));
        root.addView(shizukuCard, blockParams(16));

        LinearLayout overlayCard = card("SUPERPOSICIÓN");
        overlayStatus = addLine(overlayCard, "Permiso");
        overlayButton = pillButton("Permitir superposición");
        overlayButton.setOnClickListener(v -> onOverlayClick());
        overlayCard.addView(overlayButton, blockParams(14));
        root.addView(overlayCard, blockParams(16));

        LinearLayout monitorCard = card("MONITOR FLOTANTE");
        monitorStatus = addLine(monitorCard, "Estado");
        monitorButton = pillButton("Iniciar monitor flotante");
        monitorButton.setOnClickListener(v -> onMonitorClick());
        monitorCard.addView(monitorButton, blockParams(14));
        root.addView(monitorCard, blockParams(16));

        LinearLayout systemCard = card("SISTEMA");
        cores = addLine(systemCard, "Núcleos");
        ramDetail = addLine(systemCard, "Memoria usada");
        cpuTemp = addLine(systemCard, "Temperatura CPU/GPU");
        root.addView(systemCard, blockParams(16));

        LinearLayout batteryCard = card("BATERÍA");
        batteryLevel = addLine(batteryCard, "Nivel");
        batteryTemp = addLine(batteryCard, "Temperatura");
        root.addView(batteryCard, blockParams(16));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(gradient(new int[]{BLACK, DARK, BLACK},
                GradientDrawable.Orientation.TOP_BOTTOM, 0));
        scroll.addView(root);
        return scroll;
    }

    private LinearLayout card(String title) {
        LinearLayout c = column();
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = gradient(new int[]{0xFF1F1F1F, 0xFF080808},
                GradientDrawable.Orientation.TL_BR, 20);
        bg.setStroke(dp(1), 0x55FFFFFF);
        c.setBackground(bg);

        TextView t = text(title, 12, GRAY, true);
        t.setLetterSpacing(0.15f);
        c.addView(t);
        return c;
    }

    private TextView addLine(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);

        TextView l = text(label, 15, GRAY, false);
        TextView v = text("--", 15, WHITE, true);
        v.setGravity(Gravity.END);

        row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private Button pillButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(BLACK);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(gradient(new int[]{WHITE, LIGHT_GRAY, GRAY},
                GradientDrawable.Orientation.LEFT_RIGHT, 28));
        return b;
    }

    private GradientDrawable gradient(int[] colors, GradientDrawable.Orientation o, int radiusDp) {
        GradientDrawable g = new GradientDrawable(o, colors);
        if (radiusDp > 0) {
            g.setCornerRadius(dp(radiusDp));
        }
        return g;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
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

    private LinearLayout.LayoutParams blockParams(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
