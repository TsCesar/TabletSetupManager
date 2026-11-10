# Evolución de TabletSetupManager 0.3.0 — actualización 10/11/2026

El historial de correcciones 0.2 → 0.3 se conserva al final de este documento. **La información anterior es histórica** y no refleja por sí sola la versión consolidada de main.

## Mejoras incorporadas después de las primeras pruebas

| Área | Versión final 0.3.0 |
|---|---|
| Distribución de APK | Se integró Aptoide para Chrome, Maps, Gmail, Adobe y ciertas variantes de Waze; resuelve APK base y splits según el dispositivo y valida su firma. |
| WhatsApp | Resolución de la URL de APK desde la página oficial de Android. |
| Firefox | Descarga de APK estable desde el archivo oficial de Mozilla. |
| Chrome | Rechazo de versiones anteriores a la 138; Firefox queda como respaldo si la instalación no funciona. |
| Gmail | Se ampliaron las huellas permitidas para las variantes firmadas por Google probadas. |
| Waze APKM | Waze 5.26.5.0 ARM64 para Android 10+ desde un espejo externo, fijado por SHA-256 y validado mediante firma. Android 9 permanece bloqueado por política. |
| Formatos | Importación APKM, APKS, XAPK y ZIP, además de APK y base/splits seleccionados conjuntamente. |
| Elección del usuario | Firefox y Adobe se pueden instalar por selección individual aunque Chrome y MuPDF ya estén presentes, sin perder el respaldo automático. |
| Validación | Action #35: **96 pruebas unitarias superadas** y jobs de emuladores Android 9 y Android 14 correctos. Action #36: correcta para el mismo árbol de código que la versión final de main. |

Las pruebas en una tablet física se recogen como resultados **comunicados por el usuario** en docs/INFORME_DE_PRUEBAS.md; no se extienden sin evidencia a todos los modelos.

---

# Archivo histórico: cambios iniciales de 0.2 a 0.3

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
| 17 | Pruebas: un `main()` con 10 comprobaciones y comprobaciones de texto | Cobertura insuficiente | 47 pruebas JUnit del núcleo, 6 pruebas Robolectric (WorkManager, regla de Waze, activación protegida), verificación automática de las APK generadas y pruebas de extremo a extremo en emuladores Android 9 y 14 |
| 18 | `UpdateWorker` reintentaba indefinidamente ante cualquier error | Consumo innecesario | Máximo 3 reintentos con espera exponencial; los errores de una fuente no bloquean las demás |
| 19 | Fuentes oficiales directas no soportadas | QuickSupport solo era manual | Descargas oficiales de TeamViewer integradas, con detección de cambios por cabeceras y versión real tras descargar |

## Corregido durante las pruebas en la nube de la 0.3

| # | Problema detectado | Dónde | Corrección |
|---|---|---|---|
| 20 | F-Droid publica MuPDF, pero sin APK para la arquitectura de la tablet, y se mostraba «Fuente pendiente» | Emulador Android 14 solo x86_64 | Nuevo resultado **«No compatible»** con el motivo (arquitecturas publicadas frente a las de la tablet, o versión mínima de Android); no entra en «Instalar todas» |
| 21 | En Android 13+ el permiso de notificaciones del Updater solo se pedía al abrir el Updater | Emulador Android 14 | **«Activar y configurar Updater»** pide el permiso en la activación: los avisos funcionan aunque el técnico no abra nunca el Updater |
| 22 | El sondeo de las descargas oficiales usaba los tiempos de espera de una descarga completa | Revisión del modo sin red | Sondeo con tiempos cortos (8 s/10 s) y sin reintentos; prueba con un servidor que acepta la conexión y no responde |
| 23 | Avisos de lint `UseKtx` (19) y `DataExtractionRules` (2) | Lint en CI | `SharedPreferences.edit {}` y `toUri()` de AndroidX; reglas `dataExtractionRules` que excluyen los datos de la app de las copias en la nube y de las transferencias entre dispositivos |
| 24 | La prueba de emulador de Android 14 abría el Updater sin recrear su pantalla cuando quedaba un diálogo de permisos del sistema en su tarea | Script de prueba (no la app) | `am start --activity-clear-task`, pulsación de «Permitir», modo avión en API 30+ y espera real de la red |
| 25 | «Updater activado» se daba por bueno con solo haber abierto el Updater alguna vez | Revisión de la prueba de desinstalación | El botón final exige que el Updater haya aceptado **«Activar y configurar Updater»** (comprobaciones registradas y catálogo enviado); si el Installer reinstala el Updater, hay que activarlo de nuevo |
| 26 | El propio Updater, sin catálogo, se mostraba como «Fuente no disponible» | Emulador | Mensaje claro: sus versiones nuevas llegan por el catálogo empresarial o con un Installer más reciente |
| 27 | La prueba leía el estado «habilitado» del texto del botón (en Compose es un nodo hijo siempre habilitado) | Script de prueba | Se lee el del elemento pulsable que lo contiene; comprobación crítica antes y después de activar, con volcado de la interfaz |

## Se mantiene de la 0.2

* Arquitectura `installer` + `updater` + `core` y paquetes `es.empresa.tabletsetup.*`.
* F-Droid mediante índice JAR firmado con el certificado oficial fijado.
* Regla de Waze (Android 9 bloqueado, Android 10+ según la versión concreta) en descargas, APK locales y actualizaciones.
* FuelSoft fuera del catálogo.
* Importación manual de APK y confirmación del sistema en todas las instalaciones.
* Script `tools/create_catalog.py` (ampliado y compatible con la sintaxis anterior).
