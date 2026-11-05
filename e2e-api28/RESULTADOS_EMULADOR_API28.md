# Prueba en emulador Android API 28

| Prueba | Resultado | Detalle |
|---|---|---|
| Emulador arrancado | ✅ OK | API 28 · ABI x86_64,x86 |
| Emulador con Internet antes de empezar (las descargas de F-Droid y TeamViewer son reales) | ✅ OK | red 100 validada tras 0 s |
| Instalar Tablet Setup Installer | ✅ OK | Success |
| Installer: comprobación de versiones completada | ✅ OK |  |
| Installer: estados de las 9 aplicaciones | ✅ OK | app=NO_INSTALADA, market=NO_INSTALADA, universal=NO_INSTALADA, chrome=FUENTE_PENDIENTE, maps=FUENTE_PENDIENTE, waze=OMITIDA, whatsapp=FUENTE_PENDIENTE, gm=FUENTE_PENDIENTE, updater=NO_INSTALADA |
| FuelSoft no aparece | ✅ OK |  |
| Android 9: Waze bloqueado con «No compatible con Android 9» | ✅ OK | OMITIDA · No compatible con Android 9 |
| MuPDF disponible desde F-Droid (índice firmado real) | ✅ OK | NO_INSTALADA · Disponible 1.28.5a (270) · F-Droid |
| QuickSupport disponible (descarga oficial TeamViewer) | ✅ OK | NO_INSTALADA · Disponible en TeamViewer (descarga oficial) (versión se comprueba al descargar) |
| Tablet Updater disponible (embebido) | ✅ OK | NO_INSTALADA · Disponible 0.3.0 (3) · Incluida en el Installer |
| Lote «Instalar todas las compatibles» terminado | ✅ OK | 3 confirmación(es) de Android pulsadas |
| Resultado del lote: com.artifex.mupdf.viewer.app | ✅ OK | Instalada correctamente · 1.28.5a (270) |
| Resultado del lote: com.teamviewer.quicksupport.market | ✅ OK | Instalada correctamente · 15.83.285 (1583285) |
| Resultado del lote: es.empresa.tabletsetup.updater | ✅ OK | Instalada correctamente · 0.3.0 (3) |
| Tablet Updater instalado por el Installer (sesión PackageInstaller) | ✅ OK |  |
| MuPDF instalado | ✅ OK |  |
| QuickSupport instalado | ✅ OK |  |
| Installer: no permite desinstalarse antes de activar el Updater | ✅ OK | botón deshabilitado |
| Installer → «Activar y configurar Updater» (actividad protegida por permiso de firma) registra la comprobación periódica | ✅ OK | tarea del Updater en JobScheduler sin haber abierto el Updater |
| Installer: permite desinstalarse con el Updater activado | ✅ OK | botón habilitado |
| Updater: comprobación manual completada | ✅ OK |  |
| Updater: no ofrece instalar apps no instaladas | ✅ OK | universal |
| Updater: tarea periódica registrada en JobScheduler (WorkManager) | ✅ OK |  |
| Sin Internet: se detecta y se informa sin bloquear la app | ✅ OK | red por defecto 101 → none · QuickSupport: ERROR · Sin conexión a Internet: no se puede consultar la descarga oficial |
| Red restablecida después de la prueba sin Internet | ✅ OK | red por defecto: 103 |
| Installer retirado desde su botón final (con confirmación de Android) | ✅ OK | desinstalado |
| Updater funciona con el Installer desinstalado | ✅ OK | desinstalación: Success · comprobación: terminada (online=true) |
| Installer desinstalado | ✅ OK |  |
| Tarea periódica sigue registrada sin el Installer | ✅ OK |  |
| Sin cierres inesperados (logcat crash) | ✅ OK |  |
