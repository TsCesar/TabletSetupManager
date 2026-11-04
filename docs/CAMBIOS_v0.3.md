# Cambios de la versión 0.3 respecto a la 0.2

Revisión completa del ZIP `TabletSetupManager_v02` (README, `docs/`, módulos `core`, `installer`, `updater`, catálogo y pruebas). Se ha reutilizado el diseño existente (dos apps + `core`, F-Droid firmado, catálogo empresarial, regla de Waze, exclusión de FuelSoft) y se han corregido los problemas siguientes.

## Errores encontrados en la 0.2

| # | Problema en la 0.2 | Efecto | Corrección en la 0.3 |
|---|---|---|---|
| 1 | Sin `gradlew`, `gradlew.bat` ni `gradle-wrapper.jar` | No se podía compilar sin Android Studio | Wrapper oficial de Gradle 8.14.3 incluido y validado en CI |
| 2 | El índice F-Droid se leía con `"packages"` como **lista**, pero en `index-v1.json` es un **objeto** por paquete | La consulta de MuPDF fallaba siempre con una excepción | Lector en streaming corregido, respeta `suggestedVersionCode` (no instala betas), arquitectura y `minSdk`; usa el campo `signer` para fijar el certificado. Probado con índices firmados de prueba |
| 3 | Botón «Desinstalar este instalador» sin el permiso `REQUEST_DELETE_PACKAGES` | En Android 9+ el sistema ignora la petición | Permiso añadido solo en el Installer |
| 4 | Se podía desinstalar el Installer sin comprobar el Updater | La tablet podía quedarse sin actualizador | El botón solo se habilita con el Updater instalado, con la misma firma y activado |
| 5 | Catálogo empresarial solo protegido por HTTPS | Sin autenticidad ni integridad del contenido | Firma ECDSA P-256 obligatoria (`catalog.json.sig`), clave pública configurable, número de secuencia contra reutilización de catálogos antiguos, copia local verificada para trabajar sin red |
| 6 | Validación de APK divididas con `getPackageArchiveInfo`, que devuelve `null` para splits | Los splits no se validaban | Lector propio del manifiesto binario (paquete, versión, split) + verificación de firma de cada archivo con apksig |
| 7 | Sin comprobación de arquitectura de la APK descargada o importada | Posibles instalaciones fallidas en tablets solo de 64 bits | Análisis de `lib/<abi>` frente a `Build.SUPPORTED_ABIS` |
| 8 | Firma leída solo con `PackageManager` (comportamiento variable según fabricante) | Validación poco fiable | apksig (biblioteca de `apksigner`) evaluado para la versión de Android de la tablet; firma instalada con historial de rotación v3 |
| 9 | Descargas sin reintentos, sin reanudación y sin progreso | Fallos ante cortes de red y sin barras de progreso | Reintentos con espera, reanudación por rangos HTTP, progreso, cancelación, límite de tamaño, solo HTTPS también en redirecciones |
| 10 | Instalar Tablet Updater exigía un catálogo empresarial | No se podía instalar el Updater sin servidor | El Updater va **embebido** en el Installer (misma variante y firma, verificado antes de instalar) |
| 11 | Confirmaciones de Android solo mediante notificación | En Android 13+ sin permiso de notificaciones la cola se quedaba bloqueada | La confirmación se abre directamente con la app en primer plano; notificación solo como respaldo; botón «Mostrar de nuevo» |
| 12 | Interfaz con vistas básicas: sin selección individual, sin progreso, sin reintento | No cumplía el apartado de interfaz | Compose + Material 3, modo claro/oscuro, rejilla adaptable a la resolución, selección, progreso, reintento de fallidas |
| 13 | Updater sin configuración de frecuencia, sin activar/desactivar avisos, sin historial ni fecha de última comprobación | Requisitos del Updater incompletos | Ajustes de frecuencia (6–72 h), Wi-Fi, avisos on/off, historial y última comprobación; notificación con botón «Actualizar» |
| 14 | QuickSupport: solo un texto de ayuda | No se comprobaba el funcionamiento | Asistente: instalación, apertura con ID, detección del add-on y de su servicio de accesibilidad, accesos a Ajustes, prueba manual de conexión registrada |
| 15 | `kotlinOptions` (obsoleto) y versiones de 2024 | Avisos y riesgo de rotura | `compilerOptions`, catálogo de versiones (`gradle/libs.versions.toml`), AGP 8.10.1, Kotlin 2.2.21 |
| 16 | Versión 0.1.0 en Gradle frente a 0.2 en la documentación | Incoherencia | 0.3.0 (`versionCode` 3) en ambas apps desde `gradle.properties` |
| 17 | Pruebas: un `main()` con 10 comprobaciones y comprobaciones de texto | Cobertura insuficiente | 46 pruebas JUnit del núcleo, pruebas Robolectric de WorkManager y de la regla de Waze, verificación automática de las APK generadas |
| 18 | `UpdateWorker` reintentaba indefinidamente ante cualquier error | Consumo innecesario | Máximo 3 reintentos con espera exponencial; los errores de una fuente no bloquean las demás |
| 19 | Fuentes oficiales directas no soportadas | QuickSupport solo era manual | Descargas oficiales de TeamViewer integradas, con detección de cambios por cabeceras y versión real tras descargar |

## Se mantiene de la 0.2

* Arquitectura `installer` + `updater` + `core` y paquetes `es.empresa.tabletsetup.*`.
* F-Droid mediante índice JAR firmado con el certificado oficial fijado.
* Regla de Waze (Android 9 bloqueado, Android 10+ según la versión concreta) en descargas, APK locales y actualizaciones.
* FuelSoft fuera del catálogo.
* Importación manual de APK y confirmación del sistema en todas las instalaciones.
* Script `tools/create_catalog.py` (ampliado y compatible con la sintaxis anterior).
