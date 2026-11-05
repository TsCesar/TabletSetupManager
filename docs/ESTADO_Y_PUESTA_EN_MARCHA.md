# Estado y puesta en marcha — versión 0.3

## Alcance

Android 9+ · aplicaciones originales: MuPDF, TeamViewer QuickSupport (+ Universal Add-On cuando proceda), Google Chrome, Google Maps, Waze, WhatsApp y Gmail · **FuelSoft excluida** (procedimiento propio).

## Implementado

* Installer: detección de Android y arquitecturas, catálogo visual con iconos y estados, versión instalada y disponible, selección individual, «Instalar todas las compatibles», descargas con progreso, instalación secuencial con confirmación de Android, detección de errores, reintento de fallidas, APK individuales y divididas, importación desde almacenamiento, instalación y activación del Updater, asistente de QuickSupport, desinstalación final condicionada al Updater.
* Updater: comprobación manual y periódica (WorkManager, 24 h por defecto), frecuencia configurable, avisos on/off, botón «Actualizar» (individual, seleccionadas y todo), comparación por `versionCode`, identificación de incompatibles, historial, fecha de última comprobación, solo apps instaladas.
* Seguridad: las 10 validaciones previas a instalar, descargas HTTPS robustas, catálogo empresarial firmado, `PackageInstaller` nativo.
* Compilación sin Android Studio: Gradle Wrapper + GitHub Actions; pruebas JUnit/Robolectric; verificación automática de las APK; pruebas de extremo a extremo en emuladores Android 9 y 14 (30 de 30 correctas en cada uno; ver `docs/INFORME_DE_PRUEBAS.md`).

## Estado real por aplicación

| Aplicación | Estado | Detalle |
|---|---|---|
| MuPDF | **Automática** (instalación y actualización) | F-Droid, índice firmado. F-Droid publica APK para arm64-v8a, armeabi-v7a y x86 (consultado el 05/11/2026); en un equipo solo x86_64 aparece «No compatible» |
| TeamViewer QuickSupport | **Automática** (descarga oficial) | `dl.teamviewer.com`; se recomienda fijar su certificado en el catálogo |
| TeamViewer Universal Add-On | **Automática, opcional** | Solo si QuickSupport lo pide; requiere activar accesibilidad y prueba manual |
| Tablet Updater | **Automática** (embebido en el Installer) | Actualizaciones posteriores vía catálogo empresarial |
| Google Chrome | **Requiere catálogo empresarial o importación manual** | Normalmente preinstalada en tablets certificadas |
| Google Maps | **Requiere catálogo empresarial o importación manual** | Normalmente preinstalada |
| Gmail | **Requiere catálogo empresarial o importación manual** | Normalmente preinstalada |
| Waze | **Requiere catálogo empresarial o importación manual** · Android 9 bloqueado | «No compatible con Android 9» |
| WhatsApp | **Pendiente de fuente verificada** | Añadir la URL oficial verificada al catálogo (`type: direct`) o importar |

## Pendiente antes de usar con clientes (requiere tablets físicas)

- [ ] Instalar ambas APK en tablets reales con **Android 9** y con **Android 10 o superior** (idealmente 13–15) de las marcas habituales.
- [ ] Comprobar en tablet la descarga y validación del índice F-Droid y la instalación de MuPDF.
- [ ] Comprobar la descarga oficial de QuickSupport y del Universal Add-On, anotar la huella SHA-256 de su certificado y fijarla en el catálogo (`signerPins`).
- [ ] Probar la cola completa con confirmaciones de Android, cancelaciones, cortes de Wi-Fi y cierre de la app durante una instalación.
- [ ] Probar importación de APK divididas y paquetes `.apks`/`.xapk` en cada modelo.
- [ ] Probar el asistente de QuickSupport y una **conexión remota real** en cada modelo (incluido el aviso de ajustes restringidos en Android 13+).
- [ ] Probar Tablet Updater: activación desde el Installer, ejecución periódica real (dejar la tablet 24–48 h), notificación y botón «Actualizar».
- [ ] Publicar el catálogo empresarial firmado (si se usa) y verificarlo desde la tablet.
- [ ] Crear la clave release, compilar `TabletSetupInstaller.apk`/`TabletUpdater.apk` firmadas y planificar el registro en la verificación de desarrolladores de Android (obligatoria globalmente a partir de 2027) o la distribución por MDM.
- [ ] Revisar condiciones de redistribución de cada APK de terceros que se aloje en el catálogo.

## Nota operativa

`Tablet Setup Installer` se puede desinstalar cuando Tablet Updater está instalado con la misma firma y ha aceptado «Activar y configurar Updater» (la app lo comprueba). Las comprobaciones de WorkManager dependen de la batería, la red y el modo Doze: no ocurren a una hora exacta.
