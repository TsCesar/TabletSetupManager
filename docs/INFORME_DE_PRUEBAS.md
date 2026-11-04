# Informe de pruebas — Tablet Setup Manager 0.3

Fecha: 04/11/2026. Este informe separa con claridad:

* **A. Pruebas automatizadas ejecutadas** (resultado real, con su entorno).
* **B. Pruebas que necesitan una tablet física** (pendientes; no se declaran superadas).

> Compilar no equivale a probar: cada apartado indica qué se ha ejecutado de verdad y dónde.

## A.1 Núcleo en JVM, ejecutado en el entorno de desarrollo (sin Android SDK)

Entorno: `kotlinc` 2.2.21 (release oficial de JetBrains), JDK 21, apksig de `bundletool-all-1.18.1.jar` (release oficial de Google), Gson 2.10, JUnit 4.13.2. Comando: `tests/run_local_tests.sh`.

**Resultado: 46 pruebas, 46 correctas, 0 fallos.**

| Clase | Qué comprueba | Pruebas |
|---|---|---|
| `PolicyAndCatalogTest` | Waze bloqueado en Android 9 con el texto exacto «No compatible con Android 9»; Waze en Android 10+ sujeto al `minSdk` de la versión concreta; rangos de SDK; ABI (incluida tablet solo de 64 bits); comparación de `versionCode`; bajadas de versión; `targetSdk` mínimo de Android 14/15; FuelSoft excluida; catálogo con las 8 apps + Updater; URL integradas solo HTTPS | 8 |
| `EnterpriseCatalogTest` | Catálogo v2 con splits; FuelSoft y paquetes ajenos ignorados; rechazo de HTTP, hashes inválidos y entradas sin certificado; entradas `direct` y `signerPins`; errores estructurales (v1 sin firma, sin secuencia...); firma ECDSA válida en DER, Base64, r‖s y PEM; firma inválida si se altera el catálogo o cambia la clave | 7 |
| `FdroidIndexTest` | Lectura en streaming del formato real `index-v1.json` («packages» como objeto, en cualquier orden); `suggestedVersionCode` (no instala betas); ABI y `minSdk`; paquete inexistente; **JAR firmado** aceptado solo con el certificado fijado; rechazo de índice sin firma, alterado o inexistente | 6 |
| `ApkValidationTest` | Lector del manifiesto binario; inspección de APK firmada con apksig (paquete, versión, ABI, firmante); rechazo de archivos corruptos, sin manifiesto, **sin firma** o **alterados** (un byte); conjunto base + split válido; splits con otra versión, otro paquete u otra firma; falta de base o varias bases; paquete, versión publicada, **SHA-256** y certificado fijado; firma distinta a la instalada; misma versión; bajada de versión; **Waze con APK local en Android 9**; arquitectura incompatible; `targetSdk` antiguo; FuelSoft importada manualmente | 10 |
| `DownloaderTest` | Servidor **HTTPS real** local: descarga con SHA-256 y progreso; SHA-256 distinto (sin restos); 404; error 500 transitorio reintentado; límite de tamaño; **solo HTTPS** también en redirecciones; **reanudación tras corte de red** (Range/If-Range); **sin red** → error comprensible y reintentable; descarga condicional 304; sondeo HEAD y alternativa GET; huella de recursos sin versión; lectura en memoria con límite | 9 |
| `EngineTest` | Waze omitido sin bloquear el lote; estados según fuente/versión (fuente pendiente, instalada sin fuente, actualización, al día, incompatible, error, por verificar); firma distinta detectada antes de descargar; **el Updater solo actualiza instaladas**; «Instalar todas» excluye opcionales y deja el Updater al final; cola secuencial que continúa tras fallos y admite cancelación | 6 |

Comprobaciones cruzadas con herramientas oficiales (en el mismo entorno):

* `aapt2` oficial (incluido en bundletool) lee los manifiestos binarios generados por las pruebas con los mismos valores que el lector propio (`package`, `versionCode`, `sdkVersion`, `targetSdkVersion`).
* `tools/ApkSigCheck.java` (apksig oficial) verifica las APK de prueba firmadas: firma v3 válida y huella SHA-256 correcta.
* `tools/sign_catalog.py` (Python `cryptography`) genera claves, firma y verifica un catálogo; **el verificador Kotlin de la app acepta esa firma**, rechaza el catálogo alterado e ignora una entrada de FuelSoft.

## A.2 Comprobaciones estáticas

`python3 tests/check_project.py`: **43 de 43 correctas** (catálogo, regla de Waze, exclusión de FuelSoft, índice firmado, validaciones previas a instalar, PackageInstaller con confirmación, descargas HTTPS con reanudación, catálogo firmado, núcleo sin APIs de Android, WorkManager de 24 h, Updater sin dependencia del Installer, sin `QUERY_ALL_PACKAGES`, wrapper de Gradle, sin contraseñas release en el repositorio).

## A.3 Compilación y pruebas en GitHub Actions

_Pendiente de la ejecución en la nube (ver siguiente versión de este informe)._

## A.4 Pruebas de extremo a extremo en emulador (Android 9 API 28 y Android 14 API 34)

_Pendiente de la ejecución en la nube._

## B. Pruebas que requieren tablet física (pendientes)

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
