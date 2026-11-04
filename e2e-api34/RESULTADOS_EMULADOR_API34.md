# Prueba en emulador Android API 34

| Prueba | Resultado | Detalle |
|---|---|---|
| Emulador arrancado | ✅ OK | API 34 · ABI x86_64 |
| Instalar Tablet Setup Installer | ✅ OK | Success |
| Installer: comprobación de versiones completada | ✅ OK |  |
| Installer: estados de las 9 aplicaciones | ✅ OK | app=FUENTE_PENDIENTE, market=NO_INSTALADA, universal=NO_INSTALADA, chrome=FUENTE_PENDIENTE, maps=FUENTE_PENDIENTE, waze=FUENTE_PENDIENTE, whatsapp=FUENTE_PENDIENTE, gm=FUENTE_PENDIENTE, updater=NO_INSTALADA |
| FuelSoft no aparece | ✅ OK |  |
| Android API 34: Waze no se omite (queda sujeto a su versión/fuente) | ✅ OK | FUENTE_PENDIENTE · Sin fuente pública verificada: añádela al catálogo empresarial o importa la APK |
| MuPDF disponible desde F-Droid (índice firmado real) | ⚠️ Aviso | FUENTE_PENDIENTE · Ninguna versión publicada en F-Droid es compatible con esta tablet |
| QuickSupport disponible (descarga oficial TeamViewer) | ✅ OK | NO_INSTALADA · Disponible en TeamViewer (descarga oficial) (versión se comprueba al descargar) |
| Tablet Updater disponible (embebido) | ✅ OK | NO_INSTALADA · Disponible 0.3.0 (3) · Incluida en el Installer |
| Lote «Instalar todas las compatibles» terminado | ✅ OK | 2 confirmación(es) de Android pulsadas |
| Resultado del lote: com.teamviewer.quicksupport.market | ✅ OK | Instalada correctamente · 15.83.285 (1583285) |
| Resultado del lote: es.empresa.tabletsetup.updater | ✅ OK | Instalada correctamente · 0.3.0 (3) |
| Tablet Updater instalado por el Installer (sesión PackageInstaller) | ✅ OK |  |
| MuPDF instalado | ⚠️ Aviso |  |
| QuickSupport instalado | ✅ OK |  |
| Updater: comprobación manual completada | ✅ OK |  |
| Updater: no ofrece instalar apps no instaladas | ✅ OK | universal |
| Updater: tarea periódica registrada en JobScheduler (WorkManager) | ✅ OK |  |
| Sin Internet: se detecta y se informa sin bloquear la app | ❌ FALLO | QuickSupport: ? ·  |
| Updater funciona con el Installer desinstalado | ❌ FALLO | Success |
| Tarea periódica sigue registrada sin el Installer | ✅ OK |  |
| Sin cierres inesperados (logcat crash) | ✅ OK |  |
