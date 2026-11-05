# Verificación automática de las APK

aapt2: /usr/local/lib/android/sdk/build-tools/35.0.0/aapt2 · firma: apksigner /usr/local/lib/android/sdk/build-tools/35.0.0/apksigner

| Comprobación | Resultado | Detalle |
|---|---|---|
| Installer: archivo existe | ✅ OK | dist/TabletSetupInstaller-debug.apk |
| Installer: es un ZIP/APK real (no renombrado) | ✅ OK | 38.881.570 bytes |
| Installer: integridad ZIP (CRC) | ✅ OK | todas las entradas correctas |
| Installer: contiene AndroidManifest.xml y classes.dex | ✅ OK |  |
| Installer: válida para cualquier arquitectura | ✅ OK | bibliotecas de Compose para arm64-v8a, armeabi-v7a, x86, x86_64 |
| Installer: SHA-256 | ✅ OK | 35dcbdf6e9ac293f5c6c4efc9324c6eb384f1f67a5217fac8537a00f01e5e64a |
| Installer: packageName | ✅ OK | es.empresa.tabletsetup.installer |
| Installer: versión | ✅ OK | 0.3.0 (3) |
| Installer: minSdk 28 (Android 9) | ✅ OK | 28 |
| Installer: targetSdk | ✅ OK | 35 |
| Installer: actividad de inicio | ✅ OK | es.empresa.tabletsetup.installer.MainActivity |
| Installer: sin permisos innecesarios | ✅ OK | ACCESS_NETWORK_STATE, CONFIGURE_UPDATER, DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, INTERNET, POST_NOTIFICATIONS, REQUEST_DELETE_PACKAGES, REQUEST_INSTALL_PACKAGES |
| Installer: FuelSoft ausente del manifiesto | ✅ OK |  |
| Installer: sin tráfico HTTP en claro | ✅ OK | usesCleartextTraffic=false |
| Installer: firma APK válida (Android 9+) | ✅ OK | esquemas v2; certificado c4c88eb8554215e30b766ae81d3a53d600c94fe492433dbf4e060cdb2c7fe911 |
| Updater: archivo existe | ✅ OK | dist/TabletUpdater-debug.apk |
| Updater: es un ZIP/APK real (no renombrado) | ✅ OK | 28.890.754 bytes |
| Updater: integridad ZIP (CRC) | ✅ OK | todas las entradas correctas |
| Updater: contiene AndroidManifest.xml y classes.dex | ✅ OK |  |
| Updater: válida para cualquier arquitectura | ✅ OK | bibliotecas de Compose para arm64-v8a, armeabi-v7a, x86, x86_64 |
| Updater: SHA-256 | ✅ OK | c7ea91c54c45e5a1a459794d31f6efdba54cc041dff6d77f7c5786e7308383b1 |
| Updater: packageName | ✅ OK | es.empresa.tabletsetup.updater |
| Updater: versión | ✅ OK | 0.3.0 (3) |
| Updater: minSdk 28 (Android 9) | ✅ OK | 28 |
| Updater: targetSdk | ✅ OK | 35 |
| Updater: actividad de inicio | ✅ OK | es.empresa.tabletsetup.updater.MainActivity |
| Updater: sin permisos innecesarios | ✅ OK | ACCESS_NETWORK_STATE, DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, FOREGROUND_SERVICE, INTERNET, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, REQUEST_INSTALL_PACKAGES, WAKE_LOCK |
| Updater: FuelSoft ausente del manifiesto | ✅ OK |  |
| Updater: sin tráfico HTTP en claro | ✅ OK | usesCleartextTraffic=false |
| Updater: firma APK válida (Android 9+) | ✅ OK | esquemas v2; certificado c4c88eb8554215e30b766ae81d3a53d600c94fe492433dbf4e060cdb2c7fe911 |
| Installer: incluye Tablet Updater embebido | ✅ OK | assets/embedded/TabletUpdater.apk |
| Updater embebido idéntico al TabletUpdater.apk entregado | ✅ OK |  |
| Updater embebido: packageName | ✅ OK |  |
| Updater independiente (no contiene ni requiere el Installer) | ✅ OK |  |
| Installer y Updater firmados con la misma clave | ✅ OK | c4c88eb8554215e30b766ae81d3a53d600c94fe492433dbf4e060cdb2c7fe911 |
