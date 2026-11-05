# Diseño técnico — Tablet Setup Manager 0.3

## Módulos

| Módulo | Tipo | Contenido |
|---|---|---|
| `:core` | Biblioteca Android | **Núcleo puro** (Kotlin sin APIs de Android, probado en JVM): `model`, `policy`, `catalog`, `fdroid`, `apk`, `net`, `engine`, `util`. **Plataforma** (`platform`): dispositivo, ajustes, historial, PackageManager, repositorios de fuentes, `PackageInstaller`, receptor de resultados, notificaciones, sondeo de QuickSupport. **Interfaz** (`ui`): tema M3, componentes, `SuiteViewModel`, pantalla común, historial |
| `:installer` | Aplicación | `MainActivity`, pantallas de instalación, asistente QuickSupport, activación del Updater, desinstalación. Embebe la APK del Updater (tarea Gradle `embedUpdaterApk*`) |
| `:updater` | Aplicación | `MainActivity`, `UpdateWorker` + `UpdateScheduler` (WorkManager), `ConfigureActivity` (activación desde el Installer), ajustes e historial |

El Updater **no depende** del módulo Installer: solo de `:core`.

## Fuentes y prioridad

1. **Repositorio oficial F-Droid** — `index-v1.jar`: firma JAR verificada en streaming con el certificado del repositorio fijado (`43238d51…c9ccab`); descarga condicional con ETag; copia local válida hasta 7 días sin red.
2. **Descarga oficial del desarrollador** — URL HTTPS sin versión (TeamViewer). Se consultan cabeceras (`HEAD`, o `GET` del primer byte; tiempos de espera cortos y sin reintentos); si cambian ETag/Last-Modified se descarga la APK para conocer su versión real. Nunca se supone una versión.
3. **Catálogo empresarial firmado** — `catalog.json` + `catalog.json.sig` (ECDSA P-256/SHA-256). Entradas `versioned` (binarios autorizados con SHA-256 y certificado) y `direct` (URL oficial). `signerPins` fija certificados de fuentes integradas. `override: true` permite fijar una versión validada por encima de las fuentes 1 y 2.
4. **Importación manual** — APK, base + splits o paquetes `.apks/.xapk/.zip`.

Tablet Updater: APK embebida en el Installer (o versión superior del catálogo).

## Flujo de instalación (Installer y Updater)

1. `SuiteEngine.check()` obtiene el estado de cada app: regla del dispositivo → fuente → `StatusCalculator`. Una fuente que publica la app sin APK válida para la tablet (arquitectura o Android) da «No compatible»; sin publicar, «Fuente pendiente».
2. `BatchPlanner` elige qué instalar: Installer = no instaladas + desactualizadas (sin opcionales salvo selección); Updater = solo instaladas con versión nueva.
3. `SequentialRunner` procesa de una en una; un fallo no bloquea las siguientes.
4. `AppInstaller`: descarga (progreso, reanudación) → `ApkInspector` (ZIP, manifiesto binario, ABI, apksig) → `ApkSetValidator` (10 comprobaciones) → sesión `PackageInstaller` (`USER_ACTION_REQUIRED`) → espera del resultado.
5. `InstallStatusReceiver` recibe `STATUS_PENDING_USER_ACTION` → la actividad en primer plano muestra la confirmación del sistema (notificación si la app no está visible) → resultado final → historial.

## Updater periódico

* `UpdateScheduler`: trabajo periódico único (`KEEP` al abrir la app, `UPDATE` al cambiar ajustes), 24 h por defecto, flexibilidad 2 h, red sin medir (Wi-Fi) opcional, batería no baja, reintentos exponenciales.
* `UpdateWorker`: comprueba, registra última comprobación e historial y notifica solo si cambian las versiones pendientes (o cada 72 h si siguen pendientes). No instala nada por sí mismo.
* Activación: abrir el Updater o pulsar **Activar y configurar Updater** en el Installer (`ConfigureActivity`, protegida por permiso de firma compartido y comprobación del paquete llamante). En Android 13+ la activación pide también `POST_NOTIFICATIONS`, para que los avisos lleguen aunque nunca se abra el Updater.

## Seguridad

* HTTPS obligatorio (también redirecciones), límites de tamaño, escritura atómica, SHA-256.
* Firma de APK con apksig; firma instalada con historial de rotación; certificados fijados por F-Droid/catálogo.
* Catálogo empresarial firmado + secuencia creciente; clave pública en `BuildConfig` o configurada en la tablet.
* Sin `QUERY_ALL_PACKAGES` (solo `<queries>` de las apps gestionadas); `usesCleartextTraffic=false`; `allowBackup=false` y `dataExtractionRules` sin datos en copias en la nube ni transferencias.
* `PACKAGE_SOURCE_STORE` para fuentes del catálogo y `PACKAGE_SOURCE_LOCAL_FILE` para importaciones (Android 13+).

## Compilación

Gradle 8.14.3 · AGP 8.10.1 · Kotlin 2.2.21 (+ plugin Compose) · Compose BOM 2025.05.01 · WorkManager 2.10.0 · Gson 2.11.0 · apksig 8.10.1 · compileSdk/targetSdk 35 · minSdk 28 · JDK 17. Release minificada con R8 y firma por variables de entorno/secretos.
