# Estado y puesta en marcha del proyecto

## Alcance solicitado

Android 9+, aplicaciones originales: MuPDF, QuickSupport, Chrome, Google Maps, Waze, WhatsApp, Gmail y (cuando proceda) add-on de QuickSupport. **FuelSoft se instala aparte.**

## Qué está implementado

1. Dos APK de proyecto Android Studio: Installer temporal y Updater residente, con motor `core` compartido.
2. Interfaz en español: comprobación de aplicaciones, instalación individual, instalación secuencial de las que tengan fuente, importación manual de APK locales.
3. Fuente F-Droid mediante índice `index-v1.jar` **firmado** y comparado con el SHA-256 del certificado oficial. Sin API de simple presentación, sin cliente F-Droid instalado.
4. Catálogo empresarial HTTPS configurable **en cada app** mediante interfaz, con `versionCode`, versión Android mínima/máxima, ABI, hash y certificado esperado.
5. Android PackageInstaller con confirmación del sistema, descarga privada y comprobación de firmas; bloquea bajadas de versión y cambio inesperado de firmante.
6. Worker diario aproximado, notificación de actualizaciones disponibles, sin actualizaciones desatendidas en dispositivos normales.
7. Regla Android 9: Waze se omite en el instalador y actualizador, incluso para APK local.
8. Add-on de TeamViewer opcional: el técnico debe confirmar el complemento apropiado y habilitar Accesibilidad cuando lo pida el dispositivo.

## Fuentes de aplicaciones originales

| Aplicación | Fuente verificable | Automatizable ahora en este proyecto |
|---|---|---|
| MuPDF | F-Droid: https://f-droid.org/packages/com.artifex.mupdf.viewer.app/ | Sí, mediante índice firmado, sujeto a pruebas reales |
| QuickSupport | TeamViewer: https://www.teamviewer.com/en/download/portal/android/ | Solo si se publica URL+SHA+firma autorizadas en catálogo; o importación manual |
| Universal Add-on | TeamViewer: https://www.teamviewer.com/es/global/support/knowledge-base/teamviewer-classic/mobile/android/universal-add-on-for-android/ | Igual que QuickSupport, solo cuando proceda |
| WhatsApp | https://www.whatsapp.com/download/android | Por el momento manual; enlace oficial puede cambiar |
| Chrome | Publicación habitual en Google Play; no se verificó URL oficial APK independiente | No; requiere fuente válida y permisos de uso |
| Google Maps | Publicación habitual en Google Play; no se verificó URL oficial APK independiente | No; requiere fuente válida y permisos de uso |
| Waze | Publicación habitual en Google Play; no se verificó URL oficial APK independiente | No; requiere fuente válida y permisos de uso; Android 9 siempre omitido |
| Gmail | Publicación habitual en Google Play; no se verificó URL oficial APK independiente | No; requiere fuente válida y permisos de uso |

**No** se ha implementado la API privada/revertida de Google Play ni scraping de APKMirror. Las fuentes de Obtainium sirven como referencia, no como autorización para automatizar o redistribuir.

## Pendiente para el despliegue real

- [ ] Compilar con Android Studio: no hay Android SDK ni Gradle en el entorno donde se ha creado esta entrega.
- [ ] Probar descarga/validación del índice F-Droid en una tablet real Android 9 y 11.
- [ ] Contrastar paquete/firma/ABI de las APK reales de la empresa y corregir variantes si difieren.
- [ ] Habilitar una fuente legal, estable y actualizable para las APK propietarias, o importar versiones manualmente.
- [ ] Instalar y comprobar Updater en tablets reales y configurar su URL de catálogo también allí.
- [ ] Probar la cola de instalación con confirmaciones de Android, caídas de red y reanudación tras cierre de la app.
- [ ] Probar split APK en cada modelo de tablet y soporte de extensiones `.apk` únicamente.
- [ ] Probar sesión de TeamViewer QuickSupport y add-on en dispositivo real.
- [ ] Confirmar funcionamiento real de Chrome, Maps, Gmail, Waze y WhatsApp sin dependencias de Play Services según ROM concreta.
- [ ] Firmar builds release y establecer procedimiento de publicación con una clave de firma estable.
- [ ] Revisar permisos/condiciones de redistribución para cada aplicación de terceros.

## Nota operativa

Los orígenes disponibles no instalan automáticamente todo el catálogo hasta que se complete su configuración. `Installer` se puede desinstalar cuando la preparación inicial esté terminada y **Updater esté instalado y configurado**. La comprobación con WorkManager depende de las restricciones de batería/red y no ocurre a una hora exacta.
