package com.vantaoptimized.app;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Obtiene métricas globales del sistema ejecutando un script de shell con los permisos
 * que concede Shizuku. Cada instancia guarda su propio estado para calcular deltas.
 * Llamar siempre desde un hilo en segundo plano.
 */
public final class SystemSampler {

    public static final class Metrics {
        public int fps = -1;              // cuadros/s de la app en primer plano
        public int cpuPercent = -1;
        public int gpuPercent = -1;       // solo en GPUs Qualcomm (kgsl)
        public long ramUsedMb;
        public long ramTotalMb;
        public float tempC = Float.NaN;   // máxima temperatura CPU/GPU/SoC
        public String focusPackage = "";
    }

    private static final String SCRIPT = String.join("\n",
            "grep '^cpu ' /proc/stat | head -1",
            "grep -E '^(MemTotal|MemAvailable):' /proc/meminfo",
            "PKG=$(dumpsys window | grep -m1 mCurrentFocus | sed -n 's/.* \\([A-Za-z0-9_.]*\\)\\/[^ }]*.*/\\1/p')",
            "echo \"PKG=$PKG\"",
            "echo \"FRAMES=$(dumpsys gfxinfo \"$PKG\" 2>/dev/null | grep -m1 'Total frames rendered' | grep -o '[0-9][0-9]*' | tail -1)\"",
            "echo \"TEMP=$(for d in /sys/class/thermal/thermal_zone*; do t=$(cat $d/type 2>/dev/null); case \"$t\" in *cpu*|*gpu*|*soc*) cat $d/temp 2>/dev/null;; esac; done | sort -n | tail -1)\"",
            "echo \"GPU=$(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null)\"");

    private long prevCpuTotal = -1;
    private long prevCpuIdle = -1;
    private long prevGpuBusy = -1;
    private long prevGpuTotal = -1;
    private long prevFrames = -1;
    private long prevFrameTimeNs = -1;
    private String prevPackage = "";

    /** true si el servicio de Shizuku responde. */
    public static boolean isServiceRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** true si la app ya tiene permiso concedido en Shizuku. */
    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Muestra el diálogo de Shizuku para conceder el permiso. */
    public static void requestPermission() {
        try {
            Shizuku.requestPermission(0);
        } catch (RuntimeException ignored) {
            // Shizuku no está activo; la UI lo indica.
        }
    }

    /** Devuelve null si Shizuku no está disponible o el script falla. */
    public Metrics sample() {
        String out = runScript();
        if (out == null || out.isEmpty()) {
            return null;
        }

        long cpuTotal = -1, cpuIdle = -1;
        long memTotalKb = -1, memAvailKb = -1;
        long frames = -1;
        long gpuBusy = -1, gpuTotal = -1;
        float temp = Float.NaN;
        String pkg = "";

        for (String line : out.split("\n")) {
            try {
                if (line.startsWith("cpu ")) {
                    String[] p = line.trim().split("\\s+");
                    long total = 0;
                    for (int i = 1; i < p.length && i <= 8; i++) {
                        total += Long.parseLong(p[i]);
                    }
                    cpuTotal = total;
                    cpuIdle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
                } else if (line.startsWith("MemTotal:")) {
                    memTotalKb = Long.parseLong(line.replaceAll("\\D", ""));
                } else if (line.startsWith("MemAvailable:")) {
                    memAvailKb = Long.parseLong(line.replaceAll("\\D", ""));
                } else if (line.startsWith("PKG=")) {
                    pkg = line.substring(4).trim();
                } else if (line.startsWith("FRAMES=")) {
                    String v = line.substring(7).trim();
                    if (!v.isEmpty()) frames = Long.parseLong(v);
                } else if (line.startsWith("TEMP=")) {
                    String v = line.substring(5).trim();
                    if (!v.isEmpty()) {
                        float t = Float.parseFloat(v);
                        temp = t > 1000 ? t / 1000f : t; // algunos kernels usan miligrados
                    }
                } else if (line.startsWith("GPU=")) {
                    String[] p = line.substring(4).trim().split("\\s+");
                    if (p.length == 2) {
                        gpuBusy = Long.parseLong(p[0]);
                        gpuTotal = Long.parseLong(p[1]);
                    }
                }
            } catch (NumberFormatException ignored) {
                // Línea con formato inesperado: se omite.
            }
        }

        Metrics m = new Metrics();
        m.focusPackage = pkg;
        m.tempC = temp;

        if (memTotalKb > 0 && memAvailKb >= 0) {
            m.ramTotalMb = memTotalKb / 1024;
            m.ramUsedMb = (memTotalKb - memAvailKb) / 1024;
        }

        if (cpuTotal >= 0) {
            if (prevCpuTotal >= 0 && cpuTotal > prevCpuTotal) {
                double busy = 1.0 - (double) (cpuIdle - prevCpuIdle) / (double) (cpuTotal - prevCpuTotal);
                m.cpuPercent = (int) Math.round(Math.max(0.0, Math.min(1.0, busy)) * 100.0);
            }
            prevCpuTotal = cpuTotal;
            prevCpuIdle = cpuIdle;
        }

        if (gpuTotal > 0) {
            if (prevGpuTotal >= 0 && gpuTotal > prevGpuTotal) {
                double busy = (double) (gpuBusy - prevGpuBusy) / (double) (gpuTotal - prevGpuTotal);
                m.gpuPercent = (int) Math.round(Math.max(0.0, Math.min(1.0, busy)) * 100.0);
            }
            prevGpuBusy = gpuBusy;
            prevGpuTotal = gpuTotal;
        }

        long now = System.nanoTime();
        if (frames >= 0 && !pkg.isEmpty()) {
            if (pkg.equals(prevPackage) && prevFrames >= 0 && now > prevFrameTimeNs) {
                double seconds = (now - prevFrameTimeNs) / 1e9;
                double fps = Math.max(0, frames - prevFrames) / seconds;
                m.fps = (int) Math.min(240, Math.round(fps));
            }
            prevPackage = pkg;
            prevFrames = frames;
            prevFrameTimeNs = now;
        } else {
            prevPackage = "";
            prevFrames = -1;
        }

        return m;
    }

    private static String runScript() {
        try {
            Process p = newShizukuProcess(new String[]{"sh", "-c", SCRIPT});
            if (p == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor(3, TimeUnit.SECONDS);
            p.destroy();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Shizuku expone newProcess como método público en algunas versiones y restringido en otras.
     * Se invoca por reflexión para no depender de esa visibilidad al compilar.
     */
    private static Process newShizukuProcess(String[] cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new Object[]{cmd, null, null});
    }
}
