# Tablet Setup Manager 0.3

Dos aplicaciones Android propias para preparar tablets empresariales y mantener sus aplicaciones al día, **sin Google Play Services en nuestro software, sin Aurora Store, sin cliente F-Droid, sin inicio de sesión de Google y sin root**.

| Aplicación | Paquete | Función |
|---|---|---|
| **Tablet Setup Installer** | `es.empresa.tabletsetup.installer` | Instalador inicial: descarga, valida e instala el catálogo, instala y activa el Updater y después se desinstala. |
| **Tablet Updater** | `es.empresa.tabletsetup.updater` | Aplicación permanente: comprueba cada 24 h aprox. (WorkManager), avisa y actualiza **solo aplicaciones ya instaladas**. |
| `core` (módulo compartido) | — | Catálogo, fuentes, validaciones de seguridad, descargas, `PackageInstaller` e interfaz común (Compose + Material 3). |

Android 9 (API 28) o superior · Kotlin · Jetpack Compose + Material 3 (modo claro y oscuro) · interfaz en español · APK universales (todas las arquitecturas).

**FuelSoft no forma parte de la suite**: no está en el catálogo, se ignora si aparece en un catálogo empresarial y se rechaza si se intenta importar.

---

## 1. Dónde están las APK

Cada compilación de `main` en GitHub Actions publica:

* **Artefacto** `TabletSetupManager-APK` en la pestaña *Actions* del repositorio.
* **Rama `apk-dist`** con la última compilación: `TabletSetupInstaller-debug.apk`, `TabletUpdater-debug.apk`, `SHA256SUMS.txt`, `VERIFICACION_APK.md`, informes de pruebas y lint, y los resultados y capturas de las pruebas en emulador (`e2e-api28/`, `e2e-api34/`).

Las APK **debug** están firmadas con el keystore de pruebas del repositorio (`keystore/debug.keystore`, público, solo para tablets de prueba). Para producción se generan `TabletSetupInstaller.apk` y `TabletUpdater.apk` firmadas con vuestra clave release (sección 7).

Comprobar la integridad de una APK descargada:

```bash
sha256sum -c SHA256SUMS.txt
```

## 2. Instalación en una tablet nueva

Con la tablet conectada a Wi-Fi:

1. **Copia** `TabletSetupInstaller-debug.apk` a la tablet (USB, almacenamiento compartido o descarga) y ábrela con el gestor de archivos. Android pedirá permitir *Instalar aplicaciones desconocidas* para ese gestor: concédelo solo para esta instalación.
   *Alternativa con cable USB:* `adb install TabletSetupInstaller-debug.apk`
2. **Abre Tablet Setup Installer.** Acepta las notificaciones (sirven para avisar cuando Android pide confirmar una instalación).
3. Si aparece el aviso **«Falta autorizar Instalar aplicaciones desconocidas»**, pulsa **Autorizar** y activa el permiso para *Tablet Setup Installer*.
4. *(Opcional)* **Catálogo empresarial:** icono de engranaje → URL HTTPS del `catalog.json` y clave pública (o **Importar desde archivo de configuración**). Sin catálogo funcionan MuPDF y TeamViewer; el resto queda como *Fuente pendiente* o se importa manualmente.
5. Pulsa **Instalar todas las compatibles**. Se instalan **de una en una**: en cada aplicación Android muestra su confirmación, pulsa **Instalar**. Waze se omite automáticamente en Android 9 sin bloquear el resto. Si algo falla, pulsa **Reintentar fallidas**.
6. **Importar APK desde archivos** para aplicaciones sin fuente automática: selecciona la APK (o la base y sus splits a la vez, o un paquete `.apks`/`.xapk`/`.zip`). Si no hay certificado de referencia, se muestra la huella SHA-256 del firmante para que confirmes la procedencia.
7. **TeamViewer QuickSupport → Comprobar funcionamiento:** abre QuickSupport y verifica que muestra un ID, instala el *Universal Add-On* solo si QuickSupport lo pide, activa su servicio de accesibilidad y haz una **prueba real de conexión remota** desde un PC. Marca el resultado: la app no da por bueno el control remoto solo porque la APK esté instalada.
8. **Tablet Updater:** se instala desde el propio Installer (va incluido dentro, no necesita red). Pulsa **Activar y configurar Updater**: le pasa la configuración del catálogo y registra sus comprobaciones periódicas. En **Android 13 o superior** Android pregunta si Tablet Updater puede enviar notificaciones: pulsa **Permitir** (sin ese permiso no llegan los avisos de actualización).
9. **Desinstalar Tablet Setup Installer:** el botón solo se habilita cuando Tablet Updater está instalado, con la misma firma, y ha aceptado **Activar y configurar Updater** (abrirlo a mano no basta, porque no recibiría la configuración del catálogo). Android pedirá confirmación.

### Uso diario de Tablet Updater

* Comprueba automáticamente cada **24 horas aprox.** (configurable: 6, 12, 24, 48 o 72 h; por defecto solo con Wi-Fi) y notifica nuevas versiones compatibles. La notificación tiene el botón **Actualizar**.
* En la app: **Comprobar versiones** (icono de actualizar), **Actualizar** por aplicación, **Actualizar todo** o **Actualizar seleccionadas**.
* **Historial** con los resultados y **fecha de la última comprobación**; ajustes de **frecuencia** y **notificaciones on/off**.
* Solo actualiza aplicaciones ya instaladas, nunca instala nuevas, y siempre con la confirmación que exige Android (no hay instalaciones silenciosas en tablets convencionales).
* La primera vez que actualice algo, Android pedirá permitir *Instalar aplicaciones desconocidas* también para Tablet Updater: botón **Autorizar** del aviso.

## 3. Estado real de cada aplicación

| Aplicación | Paquete | Fuente integrada | Descarga automática | Actualización automática | Requiere |
|---|---|---|---|---|---|
| MuPDF | `com.artifex.mupdf.viewer.app` | Repositorio oficial **F-Droid** (índice `index-v1.jar` firmado, certificado fijado; hash y firmante de cada APK) | **Sí** | **Sí** (aviso + instalación con confirmación) | Nada. *Ver nota de firma.* |
| TeamViewer QuickSupport | `com.teamviewer.quicksupport.market` | **Descarga oficial de TeamViewer** (`https://dl.teamviewer.com/mobile/TeamViewerQS.apk`, publicada en su base de conocimiento) | **Sí** | **Sí**: detecta cambios por cabeceras HTTP y descarga la APK para conocer su versión real | Recomendado fijar su certificado en el catálogo (`signerPins`). |
| TeamViewer Universal Add-On | `com.teamviewer.quicksupport.addon.universal` | **Descarga oficial de TeamViewer** (`https://download.teamviewer.com/mobile/addon/TeamViewerAddon-Universal.apk`) | **Sí** (opcional, solo si QuickSupport lo pide) | **Sí** (igual que QuickSupport) | Activar su servicio de accesibilidad y prueba manual. Los add-ons específicos de fabricante los indica QuickSupport al abrirse. |
| Google Chrome | `com.android.chrome` | Ninguna pública verificable (Google lo distribuye por Play) | Solo con **catálogo empresarial** o **importación manual** | Solo con catálogo empresarial | Suele venir **preinstalada** en tablets certificadas; se detecta como tal. |
| Google Maps | `com.google.android.apps.maps` | Ninguna pública verificable | Catálogo empresarial o importación manual | Catálogo empresarial | Suele venir preinstalada. Depende de Google Play Services (de la tablet, no de nuestra app). |
| Gmail | `com.google.android.gm` | Ninguna pública verificable | Catálogo empresarial o importación manual | Catálogo empresarial | Suele venir preinstalada. Solo se instala; no se configuran cuentas. |
| Waze | `com.waze` | Ninguna pública verificable | Catálogo empresarial o importación manual (**Android 10+**) | Catálogo empresarial (Android 10+) | **Android 9: «No compatible con Android 9»** (bloqueada siempre: online, local y actualizaciones). |
| WhatsApp | `com.whatsapp` | **Pendiente de fuente verificada**: la web oficial ofrece APK, pero no se ha podido verificar una URL estable desde este entorno | Catálogo empresarial (entrada `direct` con la URL oficial verificada) o importación manual | Igual | Solo se instala; no se configuran cuentas. |
| Tablet Updater | `es.empresa.tabletsetup.updater` | **Incluida dentro del Installer** | **Sí, sin red** | Autoactualización mediante catálogo empresarial | Misma clave de firma que el Installer. |

Notas:

* **Firma de MuPDF:** la versión de F-Droid está firmada por F-Droid. Si una tablet ya tuviera MuPDF de otra tienda (otra firma), la app lo detecta y avisa de que no puede actualizarla desde F-Droid.
* **Arquitectura de MuPDF:** F-Droid publica una APK por arquitectura (arm64-v8a, armeabi-v7a y x86 a 05/11/2026, suficiente para las tablets ARM habituales). Si no hay ninguna para la de la tablet (ocurre, por ejemplo, en emuladores solo x86_64), MuPDF aparece como *No compatible* con el motivo y no se intenta instalar.
* **Nada se simula:** si una aplicación no tiene fuente funcional aparece como *Fuente pendiente* (no instalada) o *Instalada · sin fuente* y se ofrece la importación manual.
* El catálogo empresarial **no concede derechos de redistribución**: alojad solo binarios cuya distribución esté autorizada, o declarad URL oficiales del desarrollador (entradas `direct`).

## 4. Seguridad: qué se valida antes de instalar

Todas las fuentes (F-Droid, descargas oficiales, catálogo, APK locales, Updater embebido) pasan por el mismo validador (`core/.../apk/ApkSetValidator.kt`):

1. `packageName` esperado y perteneciente al catálogo (FuelSoft excluida).
2. `versionCode` igual al publicado por la fuente y mayor que el instalado.
3. `minSdkVersion`/`maxSdkVersion` frente a la tablet, y `targetSdk` mínimo que exigen Android 14/15.
4. Arquitectura (bibliotecas `lib/<abi>`) compatible con la tablet.
5. SHA-256 del archivo (índice firmado de F-Droid o catálogo firmado).
6. Firma criptográfica APK v1/v2/v3 verificada con **apksig** (la biblioteca de `apksigner`) para la versión de Android de la tablet.
7. Compatibilidad con la firma de la app instalada (incluye rotación de clave v3) y con el certificado fijado por la fuente.
8. Sin bajadas de versión.
9. APK divididas: una sola base, splits únicos, mismo paquete, misma versión y misma firma.
10. Rechazo de archivos corruptos, sin manifiesto o alterados.

Además: descargas **solo HTTPS** (también en redirecciones) con reintentos, reanudación tras cortes y límite de tamaño; el **catálogo empresarial se acepta solo si su firma ECDSA P-256 es válida** y su número de secuencia no es anterior al último aplicado; instalación mediante sesiones nativas de `PackageInstaller` con confirmación del sistema; permisos mínimos (sin `QUERY_ALL_PACKAGES`); `usesCleartextTraffic=false` y `allowBackup=false`.

## 5. Catálogo empresarial

Permite añadir aplicaciones sin fuente pública o fijar versiones y certificados **sin recompilar** las apps. Formato en `docs/catalogo-empresarial.example.json` (schemaVersion 2).

```bash
# 1) Una vez: crear el par de claves (la privada NUNCA va al repositorio ni al servidor)
python3 tools/sign_catalog.py keygen --out claves/catalog-signing-key.pem
# 2) Añadir versiones autorizadas (aapt2 + apksigner en el PATH)
python3 tools/create_catalog.py add --catalog catalog.json --package com.waze \
    --apk base.apk --apk split_config.arm64_v8a.apk --base-url https://descargas.empresa.example/waze/ --min-sdk 29
python3 tools/create_catalog.py direct --catalog catalog.json --package com.whatsapp --url https://URL-OFICIAL-VERIFICADA --signer <SHA-256>
python3 tools/create_catalog.py pin --catalog catalog.json --package com.teamviewer.quicksupport.market --signer <SHA-256>
# 3) Firmar y publicar catalog.json + catalog.json.sig en la misma carpeta HTTPS
python3 tools/sign_catalog.py sign --key claves/catalog-signing-key.pem catalog.json
python3 tools/sign_catalog.py verify --public-key claves/catalog-public-key.txt catalog.json
# 4) Archivo de configuración importable desde la tablet (o valores por defecto en la compilación)
python3 tools/sign_catalog.py config --url https://descargas.empresa.example/catalog.json \
    --public-key claves/catalog-public-key.txt --out tablet-catalog-config.json
```

Para que las APK vengan ya configuradas, define `tsm.catalog.url` y `tsm.catalog.publicKey` en `gradle.properties` o, en GitHub, las variables del repositorio `TSM_CATALOG_URL` y `TSM_CATALOG_PUBLIC_KEY`.

## 6. Compilar sin Android Studio

**En la nube (recomendado):** cada `push` a `main` ejecuta `.github/workflows/android.yml` (JDK 17, Android SDK por línea de comandos, Gradle Wrapper): compila, verifica las APK con `aapt2` y `apksigner`, ejecuta pruebas y lint y publica los resultados. También se puede lanzar a mano desde *Actions → Compilar APK → Run workflow*.

**En un PC** (Windows, macOS o Linux) con JDK 17 y las *Android SDK Command-line Tools*:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
export ANDROID_HOME=/ruta/al/sdk           # Windows: set ANDROID_HOME=C:\ruta\al\sdk
./gradlew collectDebugApks                 # Windows: gradlew.bat collectDebugApks
# Resultado: dist/TabletSetupInstaller-debug.apk y dist/TabletUpdater-debug.apk
./gradlew :core:testDebugUnitTest :updater:testDebugUnitTest lintDebug
python3 tools/verify_apks.py --installer dist/TabletSetupInstaller-debug.apk --updater dist/TabletUpdater-debug.apk
```

Versiones: Gradle 8.14.3 (wrapper incluido), Android Gradle Plugin 8.10.1, Kotlin 2.2.21, Compose BOM 2025.05.01, WorkManager 2.10.0, compileSdk/targetSdk 35, minSdk 28.

Las pruebas del núcleo también se pueden ejecutar **sin Android SDK** (solo `kotlinc` y JDK): `tests/run_local_tests.sh`.

## 7. Firma release (producción)

No hay claves de producción en el repositorio. Crear una clave propia una sola vez y guardarla de forma segura:

```bash
keytool -genkeypair -keystore tablet-release.jks -alias tablet -keyalg RSA -keysize 4096 -validity 10000
```

* **Local:** variables de entorno `TSM_RELEASE_KEYSTORE`, `TSM_RELEASE_KEYSTORE_PASSWORD`, `TSM_RELEASE_KEY_ALIAS`, `TSM_RELEASE_KEY_PASSWORD` y `./gradlew collectReleaseApks` → `dist/TabletSetupInstaller.apk` y `dist/TabletUpdater.apk` (minificadas con R8).
* **GitHub Actions:** secretos `TSM_RELEASE_KEYSTORE_BASE64` (`base64 -w0 tablet-release.jks`), `TSM_RELEASE_KEYSTORE_PASSWORD`, `TSM_RELEASE_KEY_ALIAS`, `TSM_RELEASE_KEY_PASSWORD`.

Installer y Updater **deben** firmarse con la misma clave (lo exige su permiso de firma compartido y el Installer lo comprueba). Una tablet con las APK debug debe desinstalarlas antes de instalar las release (firma distinta).

## 8. Limitaciones y avisos

* **Verificación de desarrolladores de Android:** Google exige desde el 30/09/2026, en Brasil, Indonesia, Singapur y Tailandia, que las apps instaladas en dispositivos certificados procedan de desarrolladores verificados, y prevé extenderlo a todo el mundo a partir de 2027. Antes de esa fecha conviene registrar la empresa en la Android Developer Console con los paquetes y la clave de firma de estas dos apps, o distribuirlas mediante un EMM/MDM (exento). Las instalaciones por ADB no se ven afectadas. También puede afectar a APK de terceros (p. ej. las firmadas por F-Droid).
* **Ajustes restringidos (Android 13+):** para el add-on instalado desde un archivo local, Android puede bloquear la activación de accesibilidad hasta permitirlo en *Información de la app → ⋮ → Permitir ajustes restringidos*. El asistente de QuickSupport lo indica.
* Chrome, Maps, Gmail y Waze no tienen fuente pública verificable fuera de Google Play: solo catálogo empresarial (con binarios autorizados) o importación manual.
* La confirmación de cada instalación la muestra Android; no se puede automatizar en tablets sin MDM.
* Las comprobaciones de WorkManager son aproximadas (batería, red, modo Doze).

Más detalle: `docs/ESTADO_Y_PUESTA_EN_MARCHA.md`, `docs/PLAN_TECNICO.md`, `docs/INFORME_DE_PRUEBAS.md`, `docs/CAMBIOS_v0.3.md`.

## 9. Estructura

```
core/       catálogo, políticas, F-Droid, catálogo firmado, descargas, validación APK, PackageInstaller, UI común
installer/  Tablet Setup Installer (incluye el Updater en assets/embedded/)
updater/    Tablet Updater (WorkManager, ajustes, historial, activación desde el Installer)
tools/      create_catalog.py, sign_catalog.py, verify_apks.py, ApkSigCheck.java
tests/      check_project.py, run_local_tests.sh (pruebas sin SDK)
keystore/   debug.keystore (solo pruebas)
.github/    workflow de compilación en la nube
```

## 10. Fuentes y licencias

* F-Droid, índices firmados: https://f-droid.org/docs/All_our_APIs/
* TeamViewer, Universal Add-On y descargas directas: https://www.teamviewer.com/en/global/support/knowledge-base/teamviewer-classic/mobile/android/universal-add-on-for-android/
* Android `PackageInstaller`: https://developer.android.com/reference/android/content/pm/PackageInstaller
* WorkManager: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
* Verificación de desarrolladores: https://support.google.com/android-developer-console/answer/16561738
* Aurora Store (GPL-3.0) y Obtainium (GPL-3.0) se han estudiado solo como referencia de arquitectura: **no se ha copiado código** de ellos.
* Dependencias: AndroidX/Compose y apksig (Apache-2.0), Gson (Apache-2.0), Kotlin (Apache-2.0).
