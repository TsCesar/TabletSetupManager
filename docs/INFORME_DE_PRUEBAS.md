# Informe de pruebas — Tablet Setup Manager 0.3

Fecha: 05/11/2026. Este informe separa con claridad:

* **A. Pruebas automatizadas ejecutadas** (resultado real, con su entorno).
* **B. Pruebas que necesitan una tablet física** (pendientes; no se declaran superadas).

> Compilar no equivale a probar: cada apartado indica qué se ha ejecutado de verdad y dónde.

## A.1 Núcleo en JVM, ejecutado en el entorno de desarrollo (sin Android SDK)

Entorno: `kotlinc` 2.2.21 (release oficial de JetBrains), JDK 21, apksig de `bundletool-all-1.18.1.jar` (release oficial de Google), Gson 2.10, JUnit 4.13.2. Comando: `tests/run_local_tests.sh`.

**Resultado: 47 pruebas, 47 correctas, 0 fallos.**

| Clase | Qué comprueba | Pruebas |
|---|---|---|
| `PolicyAndCatalogTest` | Waze bloqueado en Android 9 con el texto exacto «No compatible con Android 9»; Waze en Android 10+ sujeto al `minSdk` de la versión concreta; rangos de SDK; ABI (incluida tablet solo de 64 bits); comparación de `versionCode`; bajadas de versión; `targetSdk` mínimo de Android 14/15; FuelSoft excluida; catálogo con las 8 apps + Updater; URL integradas solo HTTPS | 8 |
| `EnterpriseCatalogTest` | Catálogo v2 con splits; FuelSoft y paquetes ajenos ignorados; rechazo de HTTP, hashes inválidos y entradas sin certificado; entradas `direct` y `signerPins`; errores estructurales (v1 sin firma, sin secuencia...); firma ECDSA válida en DER, Base64, r‖s y PEM; firma inválida si se altera el catálogo o cambia la clave | 7 |
| `FdroidIndexTest` | Lectura en streaming del formato real `index-v1.json` («packages» como objeto, en cualquier orden); `suggestedVersionCode` (no instala betas); ABI y `minSdk` (publicada sin APK para la tablet = **no compatible**, con el motivo; sin publicar = fuente pendiente); paquete inexistente; **JAR firmado** aceptado solo con el certificado fijado; rechazo de índice sin firma, alterado o inexistente | 6 |
| `ApkValidationTest` | Lector del manifiesto binario; inspección de APK firmada con apksig (paquete, versión, ABI, firmante); rechazo de archivos corruptos, sin manifiesto, **sin firma** o **alterados** (un byte); conjunto base + split válido; splits con otra versión, otro paquete u otra firma; falta de base o varias bases; paquete, versión publicada, **SHA-256** y certificado fijado; firma distinta a la instalada; misma versión; bajada de versión; **Waze con APK local en Android 9**; arquitectura incompatible; `targetSdk` antiguo; FuelSoft importada manualmente | 10 |
| `DownloaderTest` | Servidor **HTTPS real** local: descarga con SHA-256 y progreso; SHA-256 distinto (sin restos); 404; error 500 transitorio reintentado; límite de tamaño; **solo HTTPS** también en redirecciones; **reanudación tras corte de red** (Range/If-Range); **sin red** → error comprensible y reintentable; **servidor que acepta la conexión y no responde** → el sondeo falla en menos de 5 s y sin reintentos; descarga condicional 304; sondeo HEAD y alternativa GET; huella de recursos sin versión; lectura en memoria con límite | 10 |
| `EngineTest` | Waze omitido sin bloquear el lote; estados según fuente/versión (fuente pendiente, instalada sin fuente, actualización, al día, incompatible, **fuente sin APK para la tablet**, error, por verificar); firma distinta detectada antes de descargar; **el Updater solo actualiza instaladas**; «Instalar todas» excluye opcionales y no compatibles y deja el Updater al final; cola secuencial que continúa tras fallos y admite cancelación | 6 |

Comprobaciones cruzadas con herramientas oficiales (en el mismo entorno):

* `aapt2` oficial (incluido en bundletool) lee los manifiestos binarios generados por las pruebas con los mismos valores que el lector propio (`package`, `versionCode`, `sdkVersion`, `targetSdkVersion`).
* `tools/ApkSigCheck.java` (apksig oficial) verifica las APK de prueba firmadas: firma v3 válida y huella SHA-256 correcta.
* `tools/sign_catalog.py` (Python `cryptography`) genera claves, firma y verifica un catálogo; **el verificador Kotlin de la app acepta esa firma**, rechaza el catálogo alterado e ignora una entrada de FuelSoft.

## A.2 Comprobaciones estáticas

`python3 tests/check_project.py`: **43 de 43 correctas** (catálogo, regla de Waze, exclusión de FuelSoft, índice firmado, validaciones previas a instalar, PackageInstaller con confirmación, descargas HTTPS con reanudación, catálogo firmado, núcleo sin APIs de Android, WorkManager de 24 h, Updater sin dependencia del Installer, sin `QUERY_ALL_PACKAGES`, wrapper de Gradle, sin contraseñas release en el repositorio).

## A.3 Compilación y pruebas en GitHub Actions

Ejecución n.º 11 del flujo `.github/workflows/android.yml` (commit `c71beb3`, 05/11/2026), con resultado final **correcto**: <https://github.com/TsCesar/TabletSetupManager/actions/runs/41990403147>. Entorno: `ubuntu-latest`, JDK 17 (Temurin), Android SDK por línea de comandos (`platforms;android-35`, `build-tools;35.0.0`) y el Gradle Wrapper 8.14.3 del repositorio.

| Paso | Resultado | Detalle |
|---|---|---|
| Compilación debug (`./gradlew collectDebugApks`) | ✅ | `TabletSetupInstaller-debug.apk` (37,1 MB, lleva dentro el Updater) y `TabletUpdater-debug.apk` (27,6 MB) |
| Verificación de las APK (`tools/verify_apks.py` con `aapt2` y `apksigner` 35.0.0) | ✅ 35 de 35 | APK reales (ZIP con CRC correctos, no renombradas); manifiesto y `classes.dex`; válidas para arm64-v8a, armeabi-v7a, x86 y x86_64; paquete; versión 0.3.0 (3); minSdk 28; targetSdk 35; actividad de inicio; solo permisos necesarios; FuelSoft ausente; sin HTTP en claro; firma v2 válida; Updater embebido idéntico al entregado; Updater independiente del Installer; ambas con la misma clave |
| Pruebas del núcleo (`:core:testDebugUnitTest`) | ✅ 47 de 47 | Las mismas clases de A.1, ahora con Gradle y el classpath de Android |
| Pruebas Robolectric del Updater (`:updater:testDebugUnitTest`) | ✅ 6 de 6 | WorkManager: 24 h, Wi-Fi y etiqueta; sin duplicados al reabrir; cambio de frecuencia y red sobre el mismo trabajo. Waze con el `Build.VERSION` real de Android 9 y de Android 14. `ConfigureActivity` rechaza una activación que no viene del Installer firmado |
| Lint (`lintDebug`) | ✅ 0 errores · 2 avisos | `core` sin incidencias. `installer` y `updater`: un aviso `DataExtractionRules` cada uno, que sugiere `fullBackupContent` para Android 9–11; no tiene efecto porque `allowBackup="false"` ya desactiva toda copia de seguridad en esas versiones. Los 21 avisos de la ejecución 8 (19 `UseKtx` y 2 `DataExtractionRules`) están corregidos; estos 2 son la sugerencia complementaria que aparece al añadir `dataExtractionRules` |
| Release minificada con R8 (`assembleRelease`, sin firmar) | ✅ | La configuración release y R8 compilan. Sin la clave de la empresa no se generan APK release instalables (sección 7 del README) |

Verificación independiente, fuera de GitHub, de las APK descargadas de la rama `apk-dist` con `aapt2` (bundletool 1.18.1) y `apksig`: **35 de 35 correctas**.

```
35dcbdf6e9ac293f5c6c4efc9324c6eb384f1f67a5217fac8537a00f01e5e64a  TabletSetupInstaller-debug.apk
c7ea91c54c45e5a1a459794d31f6efdba54cc041dff6d77f7c5786e7308383b1  TabletUpdater-debug.apk
```

Firma: keystore de pruebas del repositorio (`CN=TabletSetupManager DEBUG - SOLO PRUEBAS`), certificado SHA-256 `c4c88eb8554215e30b766ae81d3a53d600c94fe492433dbf4e060cdb2c7fe911`. La ejecución 10 (mismo código de la app) produjo exactamente los mismos SHA-256: la compilación es reproducible.

## A.4 Pruebas de extremo a extremo en emulador (Android 9 API 28 y Android 14 API 34)

Misma ejecución n.º 11. Emuladores oficiales AOSP x86_64 (imagen `default`, **sin Google Play**), perfil de tablet Pixel C (2560×1800) con aceleración KVM, mediante `reactivecircus/android-emulator-runner`. Las descargas son **reales** (índice firmado de F-Droid y descarga oficial de TeamViewer desde el servidor de GitHub) y las confirmaciones de Android se pulsan con `uiautomator`, como lo haría el técnico. Script: `tools/emulator_e2e.py`.

**Resultado: 30 de 30 comprobaciones correctas en Android 9 y 30 de 30 en Android 14.**

| Comprobación | Android 9 (API 28, x86_64 + x86) | Android 14 (API 34, solo x86_64) |
|---|---|---|
| Emulador con Internet antes de empezar | ✅ red validada | ✅ red validada tras 10 s |
| Instalar Tablet Setup Installer (`adb install`) | ✅ | ✅ |
| Comprobación de versiones del Installer y estados de las 9 apps | ✅ | ✅ |
| FuelSoft no aparece | ✅ | ✅ |
| Regla de Waze | ✅ Omitida: «No compatible con Android 9» | ✅ No se omite; queda como «Fuente pendiente» (sin fuente pública) |
| MuPDF desde el índice firmado real de F-Droid | ✅ Disponible 1.28.5a (270) | ✅ «No compatible»: F-Droid publica arm64-v8a, armeabi-v7a y x86; el emulador solo es x86_64 |
| QuickSupport desde la descarga oficial de TeamViewer | ✅ Disponible | ✅ Disponible |
| Tablet Updater embebido | ✅ Disponible 0.3.0 (3) | ✅ Disponible 0.3.0 (3) |
| «Instalar todas las compatibles» con las confirmaciones de Android | ✅ 3 confirmaciones | ✅ 2 confirmaciones |
| MuPDF instalado (o no intentado si no es compatible) | ✅ 1.28.5a (270), ABI x86 | ✅ No intentado |
| QuickSupport instalado | ✅ 15.83.285 (1583285) | ✅ 15.83.285 (1583285) |
| Updater instalado por el Installer (sesión `PackageInstaller`) | ✅ 0.3.0 (3) | ✅ 0.3.0 (3) |
| El Installer **no** permite desinstalarse antes de activar el Updater | ✅ botón deshabilitado | ✅ botón deshabilitado |
| «Activar y configurar Updater» (actividad protegida por permiso de firma) registra la comprobación periódica sin abrir el Updater | ✅ | ✅ |
| Permiso de notificaciones del Updater pedido en la activación (Android 13+) | — no aplica | ✅ «Permitir» pulsado |
| El Installer permite desinstalarse con el Updater activado | ✅ botón habilitado | ✅ botón habilitado |
| Updater: comprobación manual | ✅ | ✅ |
| Updater: no ofrece instalar apps no instaladas (Universal Add-On) | ✅ | ✅ |
| Updater: tarea periódica en JobScheduler (WorkManager) | ✅ | ✅ |
| Sin Internet (Wi-Fi y datos apagados; modo avión en API 34): se detecta y se informa sin bloquear la app | ✅ QuickSupport: «Sin conexión a Internet: no se puede consultar la descarga oficial» | ✅ igual |
| Red restablecida tras la prueba | ✅ | ✅ |
| Installer retirado con su botón final y la confirmación de Android | ✅ | ✅ |
| Updater funciona con el Installer desinstalado (comprobación con red) | ✅ | ✅ |
| Tarea periódica sigue registrada sin el Installer | ✅ | ✅ |
| Sin cierres inesperados (`logcat -b crash`) | ✅ | ✅ |

Capturas en `apk-dist/e2e-api28/` y `apk-dist/e2e-api34/`: Installer al iniciar (01), confirmación de Android (02), tras el lote (03), antes y después de activar el Updater (03a/03b), Updater (04) y Updater sin Internet (05). Cada carpeta incluye además `RESULTADOS_EMULADOR_API*.md`, `resultados.json`, el logcat de la app, el volcado de la interfaz antes de activar y la tarea registrada en JobScheduler.

Qué detectaron las ejecuciones anteriores y se corrigió: puntos 20–27 de `docs/CAMBIOS_v0.3.md` (F-Droid sin APK para la arquitectura, permiso de notificaciones al activar, criterio de «Updater activado», mensaje del propio Updater y tres defectos del script de prueba).

## B. Pruebas que requieren tablet física (pendientes)

Los emuladores son x86_64 y sin servicios de Google; no sustituyen a las tablets ARM reales de los clientes.

| Prueba | Por qué necesita tablet |
|---|---|
| Instalación en las marcas y modelos reales de cliente (Android 9 y 10+) | Comportamiento del fabricante, confirmaciones y ajustes propios |
| QuickSupport: complemento del fabricante, servicio de accesibilidad, captura de pantalla y **conexión remota real** desde un PC | Requiere hardware, cuenta TeamViewer del técnico y aceptación manual |
| «Ajustes restringidos» de Android 13+ para el add-on importado localmente | Depende de la versión y del fabricante |
| Ejecución periódica real del Updater (24–48 h con la tablet en uso, Doze y batería) | WorkManager depende del estado real del dispositivo |
| Notificación de actualización con botón «Actualizar» y actualización real de una app | Necesita una nueva versión publicada por la fuente |
| Chrome, Maps, Gmail y Waze con APK autorizadas del catálogo empresarial | Requiere binarios autorizados y servidor de la empresa |
| Rendimiento con poca memoria al leer el índice F-Droid en tablets modestas | Hardware real |
| Cierre de la app o reinicio de la tablet en mitad de una instalación | Interacción manual |
