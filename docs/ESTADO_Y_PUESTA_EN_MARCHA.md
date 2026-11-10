# Estado y puesta en marcha — TabletSetupManager 0.3.0

Actualizado el 10/11/2026. Estado basado en la rama main y en los resultados de GitHub Actions #35 y #36; las pruebas físicas se basan en comprobaciones comunicadas por el usuario.

## Alcance

Dos aplicaciones propias (Tablet Setup Installer y Tablet Updater) para Android 9+; la suite no requiere cuenta Google, Aurora Store ni servicios Google Play para ejecutarse por sí misma. Esto no certifica el funcionamiento de todas las apps externas sin servicios de Google. FuelSoft queda fuera del catálogo.

## Funciones implementadas

- Catálogo y detección de Android, arquitecturas, densidades, estado de cada app y compatibilidad por versión.
- Descargas HTTPS con validaciones de integridad y de certificados; instalación secuencial mediante PackageInstaller con confirmación del sistema.
- Importación de APK, APKM, APKS, XAPK, ZIP y conjuntos base/splits; selección de variantes compatibles.
- Respaldo automático: Firefox si Chrome falla; Adobe si MuPDF falla. Ambas alternativas también se pueden instalar expresamente junto a la aplicación principal.
- Waze: APKM original versión 5.26.5.0 fijado por SHA-256 para Android 10+ ARM64; en Android 9 se omite por política.
- Updater independiente, configurado desde Installer, que comprueba las aplicaciones ya instaladas mediante WorkManager y avisa sobre actualizaciones.
- Exclusión de FuelSoft y soporte de catálogo empresarial firmado (ECDSA P-256).

## Fuentes actuales

| Aplicación | Fuente configurada | Consideraciones |
|---|---|---|
| MuPDF | Índice firmado de F-Droid | Depende de la arquitectura publicada |
| Adobe Acrobat Reader | Aptoide | Alternativa automática u opcional |
| Chrome | Aptoide | Versión mínima aceptada: Chrome 138; Android 9 puede recibir versión anterior y activar Firefox |
| Firefox | Archivo oficial de Mozilla | Alternativa automática u opcional |
| Google Maps | Aptoide | Puede requerir Google Play Services para determinadas funciones |
| Gmail | Aptoide | Instalar no equivale a comprobar inicio de sesión |
| Waze | APKM ARM64 con SHA-256 fijado; Aptoide según arquitectura | Android 9 omitido; APKM ARM64 alojado en espejo externo, no servidor oficial |
| WhatsApp | Página oficial de WhatsApp | El enlace se resuelve dinámicamente |
| TeamViewer QuickSupport y Universal Add-On | Descarga directa oficial TeamViewer | Add-On opcional; requiere permisos y prueba de asistencia |
| Tablet Updater | Embebido en Installer | Sin necesidad de red para instalar la primera versión |

Las fuentes externas pueden cambiar, dejar de funcionar o publicar variantes no compatibles. Las APK pasan por verificaciones de firma y paquete; no se debe interpretar «fuente configurada» como disponibilidad permanente.

## Resultados comprobados

- **CI #35**: compilación, 96 pruebas unitarias y emuladores API 28/34 completados correctamente.
- **CI #36**: compilación, validación y pruebas de emulador completadas en un commit con el mismo árbol de archivos que la versión final de main.
- **Tablet física (según el usuario)**: Waze se descarga mediante APKM, instala, abre, calcula rutas y usa GPS; Chrome abre webs, MuPDF abre PDF; Gmail, Maps, WhatsApp y TeamViewer se instalaron; QuickSupport permitió una conexión remota; Updater quedó configurado y siguió funcionando tras retirar Installer.
- **No verificado en tablet física en el registro disponible**: instalación voluntaria de Firefox junto a Chrome y Adobe junto a MuPDF (cambio de la Action #35), ejecución periódica de WorkManager durante 24–48 h y funcionamiento en todas las versiones y modelos de tablet.

## Lista de control antes del despliegue a clientes

- [ ] Probar las variantes de Android y CPU utilizadas realmente por la empresa (Android 9, 10+, ARM32/ARM64 y fabricantes concretos).
- [ ] Comprobar manualmente Firefox y Adobe seleccionados cuando Chrome y MuPDF ya estén instalados.
- [ ] Verificar el funcionamiento periódico de Updater, notificaciones y una actualización real publicada después de la instalación.
- [ ] Ensayar cortes de red, reinicios y cancelaciones durante la cola en hardware real.
- [ ] Configurar una clave release privada y firmar **ambas** aplicaciones con el mismo certificado; no distribuir debug como APK de producción.
- [ ] Revisar permisos y licencias de redistribución de cada APK ajena, y normativa vigente sobre verificación de desarrolladores/MDM.
- [ ] Si se requiere catálogo empresarial, crearlo, firmarlo y ensayarlo en la tablet.
- [ ] Definir la licencia del propio repositorio público si se desea autorizar a terceros a reutilizar el código.

## Flujo operativo

1. Transferir TabletSetupInstaller a la tablet y permitir instalar aplicaciones desconocidas para el origen autorizado.
2. Revisar el catálogo y usar «Instalar todas las compatibles»; confirmar cada instalación solicitada por Android.
3. Instalar opcionales por selección expresa cuando el técnico lo requiera.
4. Realizar prueba de conexión remota y validar navegador, PDF y GPS.
5. Activar y configurar Updater; después, usar la opción de retirar Installer.
6. Reiniciar y confirmar que Updater continúa instalado y conserva la configuración.

La periodicidad de WorkManager es aproximada: depende de las condiciones de red, batería y del planificador de Android.
