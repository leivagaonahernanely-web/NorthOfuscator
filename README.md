# VantaOptimized

Monitor de rendimiento para Android en blanco y negro con degradados. Muestra FPS, CPU, GPU, RAM y temperatura, y puede dibujarlos en un panel flotante sobre cualquier app.

## Requisitos del dispositivo
- Android 8.0 (API 26) o superior.
- **Shizuku** instalado y activo. Es necesario para leer métricas globales sin root.
  - Android 11 o superior: en Shizuku, activa **Depuración inalámbrica** (y mantenla activa después de reiniciar, o vuelve a iniciar Shizuku).
  - Android 10 o inferior: Shizuku se inicia con un PC vía ADB una vez tras cada reinicio.

## Permisos
- **Shizuku**: se concede desde la propia app al pulsar "Conceder permiso".
- **Mostrar sobre otras apps**: el usuario lo concede en Ajustes desde el botón de la app.
- **Notificaciones** (Android 13+): el servicio de superposición necesita mostrar una notificación fija. Si no concedes el permiso en Ajustes, el monitor sigue funcionando, pero sin el aviso.

## Cómo se calculan las métricas
- **FPS**: cuadros renderizados por la app en primer plano, medidos con `dumpsys gfxinfo` cada segundo. Si la pantalla está estática, el número baja porque la app no dibuja cuadros nuevos. No es el refresco del panel.
- **CPU**: uso total desde `/proc/stat`.
- **GPU**: solo en dispositivos Qualcomm (`/sys/class/kgsl`). En otros aparece "--".
- **RAM**: memoria total y disponible desde `/proc/meminfo`.
- **Temperatura**: la mayor de las zonas térmicas de CPU, GPU o SoC. Varía según el fabricante.
- **Batería**: nivel y temperatura desde la API pública de batería.

## Cómo compilar la APK
1. Instala [Android Studio](https://developer.android.com/studio).
2. Abre la carpeta `VantaOptimized`.
3. Espera a que termine la sincronización de Gradle.
4. **Build → Build APK(s)**.
5. La APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Antes de publicar
- Verifica la versión de `dev.rikka.shizuku:api` y `provider` en `app/build.gradle` en el [repositorio oficial de Shizuku](https://github.com/RikkaApps/Shizuku-API).
- Si `Shizuku.newProcess` no se encuentra en la versión que uses, `SystemSampler` lo invoca por reflexión; si aun así falla, revisa el `Logcat` de la app.
- Prueba en tu dispositivo: el formato de algunos archivos del sistema (temperaturas, GPU) cambia por fabricante.
- Para publicar en Play Store, cambia `applicationId` y `namespace` en `app/build.gradle`, y revisa las políticas sobre el uso de `SYSTEM_ALERT_WINDOW` y de Shizuku.

## Limitaciones honestas
- El uso de Shizuku depende de que el servicio esté activo; si el teléfono se reinicia, hay que volver a iniciarlo.
- No puede liberar RAM de otras apps: Android gestiona la memoria del sistema.
- Las lecturas de sistema cada segundo consumen algo de batería; si lo necesitas, sube el intervalo en `OverlayService` y `MainActivity`.
