# Diseño técnico — TabletSetupManager 0.3.0

Actualizado el 10/11/2026. Documento descriptivo de la implementación de main, no una promesa de compatibilidad universal.

## Arquitectura

| Módulo | Contenido |
|---|---|
| core | Dominio, catálogo, reglas de compatibilidad, cliente HTTPS, F-Droid, fuentes de APK, extracción de contenedores, inspección y validación criptográfica, interfaz Compose y adaptadores Android |
| installer | Aplicación inicial, catálogo, instalación en lote, asistencia de QuickSupport, activación de Updater y opción de retirada final |
| updater | Aplicación permanente, WorkManager, comparación de versiones, notificaciones y actualización de aplicaciones instaladas |

Updater depende de core pero no depende de Installer una vez activado.

## Resolución de fuentes

La fuente depende de cada aplicación; el catálogo empresarial puede imponer versiones/URL cuando se ha configurado y autorizado su uso.

1. **F-Droid**: MuPDF; índice JAR firmado, selección por SDK y ABI.
2. **Descarga oficial directa**: TeamViewer QuickSupport y Universal Add-On.
3. **Página oficial**: WhatsApp; se resuelve el enlace actual desde la página del desarrollador.
4. **Archivo oficial de Mozilla**: Firefox; selección de APK estable por arquitectura.
5. **Aptoide (terceros)**: Chrome, Maps, Gmail, Adobe y variantes de Waze para las que no se aplica el APKM fijado. Se verifica paquete, versión, splits y firma del desarrollador permitida; para Chrome se rechazan versiones anteriores a 138.
6. **APKM original fijado**: Waze 5.26.5.0 para Android 10+ ARM64. Se descarga desde un espejo externo con SHA-256 conocido, se extraen splits compatibles y se valida la firma de Waze. Es un paquete fijado a una versión concreta, no un actualizador de todas las versiones futuras.
7. **Catálogo empresarial firmado**: versiones/archivos y fuentes directas autorizadas; permite fijar versiones y claves sin recompilar.
8. **Importación local**: APK, APKM, APKS, XAPK, ZIP o conjunto base/splits, sujeto a las mismas validaciones aplicables.

Los nombres de las fuentes no implican que una app externa sea independiente de servicios Google ni que todas las arquitecturas dispongan de APK.

## Proceso de instalación

1. Se obtiene el perfil del dispositivo (API, ABI, densidad e idiomas), se aplica la política de compatibilidad y se resuelve cada fuente.
2. BatchPlanner organiza las aplicaciones pendientes y deja Updater para el final. Instalaciones opcionales solo por selección expresa, salvo los respaldos programados.
3. BrowserFallbackPlanner y PdfFallbackPlanner incorporan Firefox/Adobe como alternativas si procede. FallbackInstallationPolicy **omite el respaldo automático** si ya está instalada la app principal, **pero respeta la selección explícita** del usuario para instalar ambas.
4. Downloader obtiene recursos HTTPS con control de redirecciones, límites, cancelación, reintentos y SHA-256 cuando está disponible.
5. BundleArchiveExtractor y BundleSplitSelector extraen contenedores y seleccionan APK base y splits para ABI, densidad e idiomas, con límites y comprobaciones de rutas.
6. ApkInspector y ApkSetValidator verifican manifiestos, paquete esperado, versión, SDK, arquitectura, firmas y compatibilidad con la aplicación instalada.
7. PackageInstaller presenta la confirmación nativa de Android; la app espera el resultado, actualiza la interfaz y registra el historial. El flujo no realiza instalaciones silenciosas en tablets convencionales.

## Reglas particulares

- Waze bloqueado por política en Android 9; APKM ARM64 fijado solo desde Android 10.
- FuelSoft excluida en catálogo, fuentes e importaciones.
- Updater no instala aplicaciones nuevas; únicamente gestiona actualizaciones de las ya presentes.
- Las descargas de terceros con certificado no autorizado, versión no compatible o archivo distinto del hash fijado se rechazan.
- Instalación de Firefox y Adobe por decisión explícita, aunque Chrome y MuPDF estén instalados.

## Updater

- Activación desde Installer con permiso de firma compartida, intercambio de configuración y programación de WorkManager.
- Intervalo inicial aproximado de 24 h; ajustes disponibles de 6, 12, 24, 48 o 72 h, red Wi-Fi opcional y notificaciones.
- La notificación ofrece continuar el proceso de actualización, con la autorización necesaria de Android.
- Después de retirarse Installer, Updater mantiene su instalación y configuración según las pruebas comunicadas en una tablet y los emuladores. No se ha verificado en el registro disponible una ejecución automática real tras 24–48 h en hardware físico.

## Seguridad y distribución

- HTTPS también en redirecciones, límites de tamaño, SHA-256 cuando existe hash de referencia y firma de APK con apksig.
- Catálogo empresarial firmado con ECDSA P-256 y secuencia monótona; certificados de apps externas autorizados o fijados.
- Sin QUERY_ALL_PACKAGES ni tráfico HTTP en claro; copias de datos desactivadas.
- La clave de keystore/debug.keystore está públicamente disponible y se usa solo en pruebas. Las release requieren una clave privada de producción compartida entre Installer y Updater.
- El repositorio no contiene un archivo LICENSE: decidir condiciones de distribución del código antes de publicar una licencia.
- Las APK ajenas no se pueden redistribuir por el mero hecho de que sean descargables.

## Compilación y pruebas

Gradle Wrapper 8.14.3; JDK 17; Android Gradle Plugin 8.10.1; Kotlin 2.2.21; compileSdk/targetSdk 35 y minSdk 28. Compilación y pruebas mediante el flujo .github/workflows/android.yml. Véanse README.md y docs/INFORME_DE_PRUEBAS.md.
