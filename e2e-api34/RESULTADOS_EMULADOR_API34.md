# Prueba en emulador Android API 34

| Prueba | Resultado | Detalle |
|---|---|---|
| Emulador arrancado | ✅ OK | API 34 · ABI x86_64 |
| Emulador con Internet antes de empezar (las descargas de F-Droid y TeamViewer son reales) | ✅ OK | red 100 validada tras 10 s |
| Instalar Tablet Setup Installer | ✅ OK | Success |
| Installer: comprobación de versiones completada | ✅ OK |  |
| Installer: estados de las 9 aplicaciones | ✅ OK | app=NO_COMPATIBLE, market=NO_INSTALADA, universal=NO_INSTALADA, chrome=FUENTE_PENDIENTE, maps=FUENTE_PENDIENTE, waze=FUENTE_PENDIENTE, whatsapp=FUENTE_PENDIENTE, gm=FUENTE_PENDIENTE, updater=NO_INSTALADA |
| FuelSoft no aparece | ✅ OK |  |
| Android API 34: Waze no se omite (queda sujeto a su versión/fuente) | ✅ OK | FUENTE_PENDIENTE · Sin fuente pública verificada: añádela al catálogo empresarial o importa la APK |
| MuPDF sin APK de F-Droid para la arquitectura del emulador: «No compatible», sin bloquear el lote | ✅ OK | NO_COMPATIBLE · Ninguna versión publicada en F-Droid es compatible con esta tablet (arquitecturas en F-Droid: arm64-v8a, armeabi-v7a, x86; tablet: x86_64) |
| QuickSupport disponible (descarga oficial TeamViewer) | ✅ OK | NO_INSTALADA · Disponible en TeamViewer (descarga oficial) (versión se comprueba al descargar) |
| Tablet Updater disponible (embebido) | ✅ OK | NO_INSTALADA · Disponible 0.3.0 (3) · Incluida en el Installer |
| Lote «Instalar todas las compatibles» terminado | ✅ OK | 2 confirmación(es) de Android pulsadas |
| Resultado del lote: com.teamviewer.quicksupport.market | ✅ OK | Instalada correctamente · 15.83.285 (1583285) |
| Resultado del lote: es.empresa.tabletsetup.updater | ✅ OK | Instalada correctamente · 0.3.0 (3) |
| Tablet Updater instalado por el Installer (sesión PackageInstaller) | ✅ OK |  |
| MuPDF no compatible: no se intentó instalar | ✅ OK |  |
| QuickSupport instalado | ✅ OK |  |
| Installer: no permite desinstalarse antes de activar el Updater | ✅ OK | botón deshabilitado |
| Installer → «Activar y configurar Updater» (actividad protegida por permiso de firma) registra la comprobación periódica | ✅ OK | tarea del Updater en JobScheduler sin haber abierto el Updater |
| Activación: el Updater pide el permiso de notificaciones (Android 13+) y queda concedido | ✅ OK | «Permitir» pulsado en el diálogo del sistema |
| Installer: permite desinstalarse con el Updater activado | ✅ OK | botón habilitado |
| Updater: comprobación manual completada | ✅ OK |  |
| Updater: no ofrece instalar apps no instaladas | ✅ OK | universal |
| Updater: tarea periódica registrada en JobScheduler (WorkManager) | ✅ OK |  |
| Sin Internet: se detecta y se informa sin bloquear la app | ✅ OK | red por defecto 100 → none · QuickSupport: ERROR · Sin conexión a Internet: no se puede consultar la descarga oficial |
| Red restablecida después de la prueba sin Internet | ✅ OK | red por defecto: 103 |
| Installer retirado desde su botón final (con confirmación de Android) | ✅ OK | desinstalado |
| Updater funciona con el Installer desinstalado | ✅ OK | desinstalación: Success · comprobación: terminada (online=true) |
| Installer desinstalado | ✅ OK |  |
| Tarea periódica sigue registrada sin el Installer | ✅ OK |  |
| Sin cierres inesperados (logcat crash) | ✅ OK |  |
