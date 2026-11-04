# Diseño técnico — Tablet Setup Manager 0.2

## Módulos

- `:installer`: actividad nativa, instalación inicial por lote, importación manual y opción de desinstalación.
- `:updater`: actividad nativa, actualizaciones de apps ya instaladas, Worker 24 horas y avisos.
- `:core`: catálogo compartido, política de compatibilidad, índice firmado F-Droid, catálogo HTTPS administrado, descarga y sesiones del sistema Android.

## Flujo Installer

1. Enumerar aplicaciones del catálogo. Waze bloqueado automáticamente si SDK <= 28 (política en `CompatibilityPolicy`).
2. Resolver versión compatible desde F-Droid firmado o catálogo de empresa.
3. Detectar `packageName`, versión instalada y requisitos SDK/ABI.
4. Pulsar `Instalar todas` para descargar y validar una APK cada vez; esperar confirmación/resultados de Android antes de pasar a la siguiente.
5. Para fuentes propietarias no automatizadas, importar APK autorizadas con el selector de archivos de Android. El técnico debe validar procedencia.
6. Instalar y configurar Tablet Updater antes de desinstalar Installer.

## Flujo Updater

1. Revisar cada 24h aproximadamente con WorkManager y red disponible.
2. Considerar únicamente aplicaciones instaladas con versión compatible más nueva.
3. Publicar notificación cuando hay actualizaciones; la instalación se ejecuta tras acción del usuario y posibles confirmaciones Android.
4. Ningún plugin de Google Play Services; ni instalación silenciosa en tablets normales.

## Seguridad

- Descargar únicamente por HTTPS con redirecciones HTTPS, límites de tamaño y archivos temporales.
- Índice firmado de F-Droid (`index-v1.jar`) validado por firma JAR y fingerprint fijo del certificado oficial; SHA-256 de cada APK.
- Catálogo empresarial servido por HTTPS; hash de APK y certificado firmante esperado. El equipo de operaciones controla su servidor de descargas.
- Comparar identidad de paquete, versión mayor que instalada y compatibilidad de firmas. Split APK deben tener misma identidad y versión; Android valida integridad final durante la instalación.
- Los derechos de redistribución de terceros deben revisarse por separado.
- Los botones de confirmación son mecanismos oficiales de Android.

## Fuentes aún pendientes

`Chrome`, `Maps`, `Waze`, `Gmail`, `WhatsApp` y `TeamViewer QuickSupport` no cuentan con feeds de versión automáticos listos para conectar. Se puede realizar instalación con APK local autorizada y un catálogo interno de versiones **si se dispone de derechos para alojar el paquete**. No hay acceso a Play Store mediante API extraoficial ni scraping automático de APKMirror.

## Estado y comprobaciones

- Las pruebas de política JVM y estructura pasan.
- No hay compilación Android ni test de hardware; ver `ESTADO_Y_PUESTA_EN_MARCHA.md`.
- La persistencia de la cola y la respuesta ante cierre inesperado durante una instalación deben probarse y endurecerse antes de producción.
