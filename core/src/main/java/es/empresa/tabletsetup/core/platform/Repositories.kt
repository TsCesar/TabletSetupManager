package es.empresa.tabletsetup.core.platform



import android.content.Context

import androidx.core.content.edit

import com.google.gson.JsonArray

import com.google.gson.JsonObject

import com.google.gson.JsonParser

import es.empresa.tabletsetup.core.apk.ApkInspector

import es.empresa.tabletsetup.core.catalog.AppCatalog

import es.empresa.tabletsetup.core.catalog.CatalogSignature

import es.empresa.tabletsetup.core.catalog.EnterpriseCatalog

import es.empresa.tabletsetup.core.catalog.EnterpriseCatalogParser

import es.empresa.tabletsetup.core.fdroid.FdroidIndexReader

import es.empresa.tabletsetup.core.fdroid.FdroidLookup

import es.empresa.tabletsetup.core.fdroid.FdroidRepo

import es.empresa.tabletsetup.core.fdroid.FdroidSelector

import es.empresa.tabletsetup.core.model.ApkAsset

import es.empresa.tabletsetup.core.model.AppRelease

import es.empresa.tabletsetup.core.model.BuiltInSource

import es.empresa.tabletsetup.core.model.DeviceProfile

import es.empresa.tabletsetup.core.model.ManagedApp

import es.empresa.tabletsetup.core.model.Mode

import es.empresa.tabletsetup.core.model.Resolution

import es.empresa.tabletsetup.core.model.RuleResult

import es.empresa.tabletsetup.core.model.SourceKind

import es.empresa.tabletsetup.core.model.ValidationException

import es.empresa.tabletsetup.core.net.DownloadException

import es.empresa.tabletsetup.core.net.Downloader

import es.empresa.tabletsetup.core.net.ProbeResult

import es.empresa.tabletsetup.core.net.ResourceFingerprint

import es.empresa.tabletsetup.core.policy.CompatibilityPolicy

import es.empresa.tabletsetup.core.sources.AptoideApkInfo

import es.empresa.tabletsetup.core.sources.AptoideSource
import es.empresa.tabletsetup.core.sources.FirefoxArchiveSource

import es.empresa.tabletsetup.core.sources.AptoideSplitSelector

import es.empresa.tabletsetup.core.sources.WhatsAppSource

import java.io.File



/** Estado del catálogo empresarial tras intentar cargarlo. */



sealed interface CatalogState {



    data object NotConfigured : CatalogState

    data class Loaded(val catalog: EnterpriseCatalog, val fromCache: Boolean) : CatalogState

    data class Failed(val message: String) : CatalogState



}



/** Estado del índice F-Droid. */



sealed interface FdroidState {



    data class Loaded(val lookups: Map<String, FdroidLookup>, val fromCache: Boolean) : FdroidState

    data class Failed(val message: String) : FdroidState



}



/** Fuentes compartidas obtenidas una vez por comprobación. */



data class SourcesSnapshot(

    val catalog: CatalogState,

    val fdroid: FdroidState?,

    val probes: Map<String, Result<ProbeResult>>,

    val resolvedUrls: Map<String, String> = emptyMap(),

    val aptoideResults: Map<String, Result<AptoideApkInfo>> = emptyMap(),

)



/**

 * Catálogo empresarial: HTTPS + firma ECDSA obligatoria + protección frente a reutilización

 * de catálogos antiguos. Si no hay red se usa la última copia verificada (se vuelve a verificar).

 */



class EnterpriseCatalogRepository(context: Context, private val settings: SuiteSettings, private val downloader: Downloader) {



    private val dir = File(context.applicationContext.filesDir, "catalogo").apply { mkdirs() }

    private val cachedJson = File(dir, "catalog.json")

    private val cachedSig = File(dir, "catalog.json.sig")

    fun load(allowNetwork: Boolean): CatalogState {

        val url = settings.catalogUrl.trim()

        if (url.isEmpty()) return CatalogState.NotConfigured

        val keyText = settings.catalogPublicKey.trim()

        if (keyText.isEmpty()) {

            return CatalogState.Failed("Falta la clave pública del catálogo: sin ella no se puede comprobar su autenticidad")

        }



        return try {

            EnterpriseCatalogParser.requireHttps(url)

            val key = CatalogSignature.parsePublicKey(keyText)

            val fresh = if (allowNetwork) {

                try {

                    downloader.fetch(url, MAX_CATALOG_BYTES) to downloader.fetch("$url.sig", MAX_SIGNATURE_BYTES)

                } catch (e: DownloadException) {



                    if (!cachedJson.exists()) throw e

                    null

                }

            } else {



                null

            }



            val (data, signature) = fresh ?: if (cachedJson.exists() && cachedSig.exists()) {

                cachedJson.readBytes() to cachedSig.readBytes()

            } else {



                throw ValidationException("Catálogo no disponible sin conexión")

            }



            if (!CatalogSignature.verify(data, signature, key)) {

                return CatalogState.Failed("Firma del catálogo NO válida: no se confía en su contenido")

            }



            val catalog = EnterpriseCatalogParser.parse(String(data, Charsets.UTF_8), AppCatalog.packageNames)

            if (catalog.sequence < settings.lastCatalogSequence) {

                return CatalogState.Failed(

                    "El catálogo recibido (secuencia ${catalog.sequence}) es más antiguo que el último aplicado " +

                        "(${settings.lastCatalogSequence}): posible reutilización de un catálogo antiguo"

                )

            }



            if (fresh != null) {

                cachedJson.writeBytes(data)

                cachedSig.writeBytes(signature)

            }



            settings.lastCatalogSequence = catalog.sequence

            CatalogState.Loaded(catalog, fromCache = fresh == null)

        } catch (e: DownloadException) {



            CatalogState.Failed("Catálogo empresarial: ${e.message}")

        } catch (e: ValidationException) {



            CatalogState.Failed(e.message ?: "Catálogo empresarial no válido")

        } catch (e: Exception) {



            CatalogState.Failed("Catálogo empresarial no disponible (${e.javaClass.simpleName})")

        }

    }



    /** Olvida la copia local (al cambiar de URL o de clave). */

    fun reset() {

        cachedJson.delete()

        cachedSig.delete()

        settings.lastCatalogSequence = 0

    }



    companion object {

        const val MAX_CATALOG_BYTES = 2L * 1024 * 1024

        const val MAX_SIGNATURE_BYTES = 16L * 1024

    }



}



/** Índice firmado de F-Droid con descarga condicional (ETag) y caché. */



class FdroidRepository(context: Context, private val downloader: Downloader) {



    private val dir = File(context.applicationContext.cacheDir, "fdroid").apply { mkdirs() }

    private val jar = File(dir, FdroidRepo.INDEX_JAR)

    private val prefs = context.applicationContext.getSharedPreferences("fdroid_index", Context.MODE_PRIVATE)

    private var parsedFor: Pair<Long, Set<String>>? = null

    private var parsed: Map<String, FdroidLookup>? = null

    @Synchronized

    fun load(packages: Set<String>, allowNetwork: Boolean, maxAgeMs: Long): FdroidState {

        val now = System.currentTimeMillis()

        val downloadedAt = prefs.getLong("downloaded_at", 0L)

        var fromCache = true

        if (allowNetwork && (!jar.exists() || now - downloadedAt > maxAgeMs)) {

            try {

                val tmp = File(dir, "index-v1.jar.new")

                val outcome = downloader.download(

                    url = FdroidRepo.REPO_URL + FdroidRepo.INDEX_JAR,

                    destination = tmp,

                    maxBytes = FdroidRepo.MAX_INDEX_BYTES,

                    conditionalEtag = if (jar.exists()) prefs.getString("etag", null) else null,

                    conditionalLastModified = if (jar.exists()) prefs.getString("last_modified", null) else null,

                )

                if (!outcome.notModified) {

                    // Se verifica (firma + certificado fijado) antes de sustituir la copia buena anterior.

                    val verified = FdroidIndexReader.read(tmp, packages)

                    jar.delete()

                    if (!tmp.renameTo(jar)) {

                        tmp.copyTo(jar, overwrite = true); tmp.delete()

                    }



                    parsed = verified

                    parsedFor = jar.lastModified() to packages

                }



                prefs.edit {

                    putLong("downloaded_at", now)

                    putString("etag", outcome.etag)

                    putString("last_modified", outcome.lastModified)

                }



                fromCache = false

            } catch (e: Exception) {



                val tooOld = now - downloadedAt > MAX_OFFLINE_AGE_MS

                if (!jar.exists() || tooOld) {

                    return FdroidState.Failed(

                        when (e) {

                            is DownloadException -> "F-Droid: ${e.message}"

                            is ValidationException -> e.message ?: "Índice F-Droid no válido"

                            else -> "F-Droid no disponible (${e.javaClass.simpleName})"

                        }

                    )

                }

            }

        }



        if (!jar.exists()) return FdroidState.Failed("Índice F-Droid no descargado (sin conexión)")

        return try {

            val key = jar.lastModified() to packages

            val lookups = parsed?.takeIf { parsedFor == key } ?: FdroidIndexReader.read(jar, packages).also {

                parsed = it

                parsedFor = key

            }



            FdroidState.Loaded(lookups, fromCache)

        } catch (e: ValidationException) {



            jar.delete()

            FdroidState.Failed(e.message ?: "Índice F-Droid no válido")

        }

    }



    companion object {

        const val MAX_OFFLINE_AGE_MS = 7L * 24 * 60 * 60 * 1000

    }



}



/** Versión conocida de una descarga oficial directa (la URL no incluye versión). */



data class DirectCacheEntry(

    val fingerprint: String?,

    val versionCode: Long,

    val versionName: String?,

    val sha256: String,

    val minSdk: Int,

    val abis: Set<String>,

    val signers: Set<String>,

    val checkedAt: Long,

)



/**

 * Descargas oficiales directas: se consultan las cabeceras HTTP (tamaño, ETag, fecha) y, si cambian,

 * se descarga la APK para conocer su versión real (nunca se supone una versión).

 */



class DirectSourceRepository(context: Context, private val downloader: Downloader) {



    private val prefs = context.applicationContext.getSharedPreferences("direct_sources", Context.MODE_PRIVATE)

    val prefetchDir = File(context.applicationContext.filesDir, "prefetch").apply { mkdirs() }

    /** Proveedor de enlaces oficiales de WhatsApp. */

    private val whatsAppSource = WhatsAppSource(downloader)

    /** Proveedor de aplicaciones desde Aptoide. */

    private val aptoideSource = AptoideSource(downloader)

    private val firefoxArchiveSource = FirefoxArchiveSource(downloader)

    fun resolveFirefoxArchive(supportedAbis: List<String>): Result<String> = try {
        Result.success(firefoxArchiveSource.resolveDownloadUrl(supportedAbis))
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**

    * Consulta la página oficial y obtiene su enlace APK.

    * Un fallo se devuelve como Result.failure.

    */

    fun resolveWhatsAppPage(pageUrl: String): Result<String> = try {

        Result.success(

            whatsAppSource.resolveDownloadUrl(pageUrl)

        )

    } catch (e: Exception) {



        Result.failure(e)

    }



/**

 * Consulta Aptoide para obtener los metadatos

 * y la URL APK de una aplicación autorizada.

 */



    fun resolveAptoide(

        packageName: String,

        sdkInt: Int,

        supportedAbis: List<String>,

        densityDpi: Int

    ): Result<AptoideApkInfo> = try {

        Result.success(

            aptoideSource.lookup(

                packageName = packageName,

                sdkInt = sdkInt,

                supportedAbis = supportedAbis,

                densityDpi = densityDpi

            )

        )

    } catch (e: Exception) {



        Result.failure(e)

    }



    fun probe(url: String): Result<ProbeResult> = try {

        Result.success(downloader.probe(url))

    } catch (e: Exception) {



        Result.failure(e)

    }



    fun cached(packageName: String): DirectCacheEntry? {

        val text = prefs.getString(packageName, null) ?: return null

        return try {

            val o = JsonParser.parseString(text).asJsonObject

            DirectCacheEntry(

                fingerprint = o.get("fp")?.takeIf { !it.isJsonNull }?.asString,

                versionCode = o.get("vc").asLong,

                versionName = o.get("vn")?.takeIf { !it.isJsonNull }?.asString,

                sha256 = o.get("sha").asString,

                minSdk = o.get("min").asInt,

                abis = o.getAsJsonArray("abis").map { it.asString }.toSet(),

                signers = o.getAsJsonArray("signers").map { it.asString }.toSet(),

                checkedAt = o.get("t").asLong,

            )

        } catch (e: Exception) {



            null

        }

    }



    fun remember(packageName: String, entry: DirectCacheEntry) {

        val o = JsonObject().apply {

            addProperty("fp", entry.fingerprint)

            addProperty("vc", entry.versionCode)

            addProperty("vn", entry.versionName)

            addProperty("sha", entry.sha256)

            addProperty("min", entry.minSdk)

            add("abis", JsonArray().apply { entry.abis.forEach { add(it) } })

            add("signers", JsonArray().apply { entry.signers.forEach { add(it) } })

            addProperty("t", entry.checkedAt)

        }



        prefs.edit { putString(packageName, o.toString()) }

    }



    /** APK descargada y verificada previamente (para no repetir la descarga al instalar). */

    fun prefetched(packageName: String): File = File(prefetchDir, "$packageName.apk")

    /** Construye la versión publicada: conocida si las cabeceras no han cambiado desde la última descarga. */

    fun release(app: ManagedApp, url: String, publisher: String, probe: ProbeResult?, pins: Set<String>): AppRelease {

        val cache = cached(app.packageName)

        val known = cache != null && probe != null && probe.fingerprint.matches(ResourceFingerprint.decode(cache.fingerprint))

        return AppRelease(

            packageName = app.packageName,

            versionCode = if (known) cache!!.versionCode else null,

            versionName = if (known) cache!!.versionName else null,

            minSdk = if (known) cache!!.minSdk else null,

            abis = if (known) cache!!.abis else emptySet(),

            assets = listOf(

                ApkAsset(url = url, sha256 = if (known) cache!!.sha256 else null, fileName = "base.apk", sizeBytes = probe?.contentLength)

            ),

            expectedSigners = pins,

            origin = SourceKind.OFFICIAL_DIRECT,

            originLabel = "$publisher (descarga oficial)",

        )

    }



}



/** Tablet Updater incluido como asset dentro del Installer. */



class EmbeddedUpdater(context: Context) {



    private val appContext = context.applicationContext

    private val target = File(appContext.cacheDir, "embedded/TabletUpdater.apk")

    fun isAvailable(): Boolean = try {

        appContext.assets.list("embedded")?.contains(ASSET_NAME) == true

    } catch (e: Exception) {



        false

    }



    /** Copia la APK embebida a un archivo temporal. */

    fun extract(): File {

        target.parentFile?.mkdirs()

        appContext.assets.open("embedded/$ASSET_NAME").use { input ->

            target.outputStream().use { output -> input.copyTo(output) }

        }



        return target

    }



    @Volatile

    private var memo: AppRelease? = null

    fun release(device: DeviceProfile, ownSigners: Set<String>): AppRelease? {

        memo?.let { return it }

        if (!isAvailable()) return null

        val info = ApkInspector.inspect(extract(), device.sdkInt)

        if (info.manifest.packageName != AppCatalog.UPDATER) {

            throw ValidationException("La APK embebida no es Tablet Updater")

        }



        return AppRelease(

            packageName = AppCatalog.UPDATER,

            versionCode = info.manifest.versionCode,

            versionName = info.manifest.versionName,

            minSdk = info.manifest.minSdk,

            assets = listOf(ApkAsset(EMBEDDED_URL, info.sha256, ASSET_NAME, info.sizeBytes)),

            // Debe estar firmado con la misma clave que el propio Installer.

            expectedSigners = ownSigners,

            origin = SourceKind.EMBEDDED,

            originLabel = "Incluida en el Installer",

        ).also { memo = it }

    }



    companion object {

        const val ASSET_NAME = "TabletUpdater.apk"

        const val EMBEDDED_URL = "embedded://TabletUpdater.apk"

    }



}



/**

 * Resolución de la versión disponible por aplicación, respetando las prioridades:

 *  1. repositorio oficial (F-Droid)  2. descarga oficial del desarrollador

 *  3. catálogo empresarial firmado   4. importación manual.

 * Una entrada del catálogo con "override": true prevalece (para fijar versiones validadas).

 */



class ReleaseResolver(

    private val catalogRepository: EnterpriseCatalogRepository,

    private val fdroidRepository: FdroidRepository,

    private val directRepository: DirectSourceRepository,

    private val embedded: EmbeddedUpdater,

    private val packages: PackageStateReader,

) {



    fun snapshot(apps: List<ManagedApp>, device: DeviceProfile, allowNetwork: Boolean, fdroidMaxAgeMs: Long): SourcesSnapshot {

        val catalog = catalogRepository.load(allowNetwork)

        val fdroidApps = apps.filter { it.builtInSource == BuiltInSource.FDroid && isAllowed(it, device) }

        val fdroid = if (fdroidApps.isEmpty()) null else fdroidRepository.load(fdroidApps.map { it.packageName }.toSet(), allowNetwork, fdroidMaxAgeMs)

        val probes = mutableMapOf<String, Result<ProbeResult>>()

        val resolvedUrls = mutableMapOf<String, String>()

        val aptoideResults = mutableMapOf<String, Result<AptoideApkInfo>>()

        if (allowNetwork) {

            apps.filter { isAllowed(it, device) }.forEach { app ->

                val direct = directUrl(app, catalog)

                when {

                    // Descargas directas existentes: TeamViewer, etc.

                    direct != null -> {

                        probes[app.packageName] =

                            directRepository.probe(direct)

                    }



                    // Página oficial de WhatsApp

                    app.builtInSource is BuiltInSource.OfficialPage -> {

                        val source = app.builtInSource

                        val resolved = directRepository.resolveWhatsAppPage(

                            source.pageUrl

                        )

                        probes[app.packageName] = resolved.fold(

                            onSuccess = { url ->

                                val probeResult = directRepository.probe(url)

                                if (probeResult.isSuccess) {

                                    resolvedUrls[app.packageName] = url

                                }



                                probeResult

                            },

                            onFailure = { error ->

                                Result.failure(error)

                            }

                        )

                    }



                    // Firefox se descarga directamente del archivo oficial de Mozilla.
                    app.builtInSource == BuiltInSource.MozillaArchive -> {
                        val result = directRepository.resolveFirefoxArchive(device.supportedAbis)
                        probes[app.packageName] = result.fold(
                            onSuccess = { url ->
                                val probeResult = directRepository.probe(url)
                                if (probeResult.isSuccess) resolvedUrls[app.packageName] = url
                                probeResult
                            },
                            onFailure = { Result.failure(it) }
                        )
                    }

                    // Chrome, Google Maps, Gmail y Waze mediante Aptoide

                    app.builtInSource is BuiltInSource.Aptoide -> {

                        aptoideResults[app.packageName] =

                            directRepository.resolveAptoide(

                                packageName = app.packageName,

                                sdkInt = device.sdkInt,

                                supportedAbis = device.supportedAbis,

                                densityDpi = device.densityDpi

                            )

                    }

                }

            }

        }



        return SourcesSnapshot(

            catalog = catalog,

            fdroid = fdroid,

            probes = probes,

            resolvedUrls = resolvedUrls,

            aptoideResults = aptoideResults,

        )

    }



    private fun isAllowed(app: ManagedApp, device: DeviceProfile) =

        CompatibilityPolicy.deviceRule(app.packageName, device, app.minDeviceSdk) is RuleResult.Allowed

    private fun directUrl(app: ManagedApp, catalog: CatalogState): String? {

        val entry = (catalog as? CatalogState.Loaded)?.catalog?.directSources?.get(app.packageName)

        val builtIn = app.builtInSource as? BuiltInSource.OfficialDirect

        return when {

            entry != null && (entry.override || builtIn == null) -> entry.url

            builtIn != null -> builtIn.url

            else -> null

        }

    }



    fun resolve(app: ManagedApp, snapshot: SourcesSnapshot, device: DeviceProfile, mode: Mode): Resolution {

        val catalog = (snapshot.catalog as? CatalogState.Loaded)?.catalog

        val pins = catalog?.signerPins?.get(app.packageName).orEmpty()

        val catalogReleases = catalog?.releases?.get(app.packageName).orEmpty()

        val bestCatalog = catalogReleases

            .filter { CompatibilityPolicy.releaseRule(it.release, device) is RuleResult.Allowed }

            .maxByOrNull { it.release.versionCode ?: 0L }

        val directEntry = catalog?.directSources?.get(app.packageName)

        if (bestCatalog != null && bestCatalog.override) return Resolution.Available(bestCatalog.release)

        if (directEntry != null && directEntry.override) return directResolution(app, directEntry.url, "Empresa", snapshot, pins + directEntry.signers)

        when (val source = app.builtInSource) {

            BuiltInSource.FDroid -> {

                when (val state = snapshot.fdroid) {

                    is FdroidState.Loaded -> {

                        val lookup = state.lookups[app.packageName]

                        if (lookup != null) {

                            when (val selection = FdroidSelector.choose(lookup, app.packageName, device)) {

                                is FdroidSelector.Selection.Found -> {

                                    val release = selection.release

                                    return Resolution.Available(if (pins.isEmpty()) release else release.copy(expectedSigners = release.expectedSigners + pins))

                                }



                                is FdroidSelector.Selection.Unavailable -> if (bestCatalog == null) {

                                    return if (selection.incompatible) Resolution.Incompatible(selection.reason) else Resolution.NoSource(selection.reason)

                                }

                            }

                        }

                    }



                    is FdroidState.Failed -> if (bestCatalog == null) return Resolution.Failed(state.message)

                    null -> Unit

                }

            }



            is BuiltInSource.OfficialDirect -> return directResolution(app, source.url, source.publisher, snapshot, pins)

            is BuiltInSource.OfficialPage -> {

                val url = snapshot.resolvedUrls[app.packageName]

                if (url != null) {

                    return directResolution(

                        app = app,

                        url = url,

                        publisher = source.publisher,

                        snapshot = snapshot,

                        pins = pins

                    )

                }



                val error = snapshot.probes[app.packageName]

                    ?.exceptionOrNull()

                if (error != null) {

                    return Resolution.Failed(

                        "${source.publisher}: ${error.message ?: "No se pudo obtener el enlace APK"}"

                    )

                }

            }



            BuiltInSource.Embedded -> if (mode == Mode.INSTALLER) {

                val embeddedRelease = try {

                    embedded.release(device, packages.ownSigners())

                } catch (e: Exception) {



                    null

                }



                val best = listOfNotNull(embeddedRelease, bestCatalog?.release).maxByOrNull { it.versionCode ?: 0L }

                if (best != null) return Resolution.Available(best)

            }



            is BuiltInSource.Aptoide -> {

                val result = snapshot.aptoideResults[app.packageName]

                val apk = result?.getOrNull()

                if (apk == null) {

                    if (result?.isFailure == true &&

                        bestCatalog == null && directEntry == null

                    ) {

                        return Resolution.Failed(

                            "Aptoide: ${result.exceptionOrNull()?.message ?: "Consulta fallida"}"

                        )

                    }

                } else {



                    // Rechazar versiones antiguas de Chrome.

                    // Chrome 138 fue la ultima version compatible con Android 9.

                    if (app.packageName == "com.android.chrome") {

                        val majorVersion = apk.versionName

                            ?.substringBefore(".")

                            ?.toIntOrNull()



                        if (majorVersion == null || majorVersion < 138) {

                            return Resolution.NoSource(

                                "Chrome ${apk.versionName ?: "desconocida"} " +

                                    "es demasiado antigua. Se requiere una " +

                                    "fuente con una version compatible mas reciente."

                            )

                        }

                    }



                    // Referencias SHA-256 de certificados utilizados por Google.

                    // La firma real de cada APK se comprueba antes de instalar.

                    val knownGoogleSigners = when (app.packageName) {

                        "com.android.chrome" -> setOf(

                            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"

                        )



                        "com.google.android.apps.maps" -> setOf(

                            // Certificado Google Android (3891), verificado para Google Maps.
                            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83",

                            // Certificado alternativo de Google ya admitido para variantes de Maps.
                            "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053"

                        )

                        "com.google.android.gm" -> setOf(

                            "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053"

                        )



                        // Waze: certificado público de APKMirror de la versión 5.26.0.0.
                        "com.waze" -> setOf(
                            "03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7"
                        )

                        // Adobe: huella SHA-256 del certificado oficial publicada en APKMirror.
                        "com.adobe.reader" -> setOf(
                            "b6dd0562256487fcd6c98cde137858ef50d9adb9f9cd2f1ca58c5357efdf0faf"
                        )

                        else -> emptySet()

                    }



                    // Prioridad: catalogo firmado, referencias integradas, instalacion existente.

                    val trustedSigners = when {

                        pins.isNotEmpty() -> pins

                        knownGoogleSigners.isNotEmpty() -> knownGoogleSigners

                        else -> packages.installed(app.packageName)?.signers.orEmpty()

                    }



                    when {

                        trustedSigners.isEmpty() -> {

                            if (bestCatalog == null && directEntry == null) {

                                return Resolution.NoSource(

                                    "Falta verificar el certificado de firma de ${app.label}"

                                )

                            }

                        }



                        apk.minSdk != null && apk.minSdk > device.sdkInt -> {

                            if (bestCatalog == null && directEntry == null) {

                                return Resolution.Incompatible(

                                    "${app.label} requiere Android SDK ${apk.minSdk} o superior"

                                )

                            }

                        }



                        apk.versionCode <= 0L -> {

                            return Resolution.Failed(

                                "Aptoide devolvió una versión no válida"

                            )

                        }

                        else -> {

                            // Escoger solamente los splits correspondientes al dispositivo.

                            val selectedSplits = try {

                                AptoideSplitSelector.select(

                                    apk = apk,

                                    supportedAbis = device.supportedAbis,

                                    densityDpi = device.densityDpi,

                                    preferredLocales = device.preferredLocales

                                )

                            } catch (e: ValidationException) {

                                return Resolution.Failed(

                                    "Aptoide: ${e.message ?: "No se pudieron seleccionar las APK divididas"}"

                                )

                            }



                            // APK base y archivos adicionales para la misma sesión de instalación.

                            val baseAsset = ApkAsset(

                                url = apk.downloadUrl,

                                sha256 = null,

                                fileName = "base.apk"

                            )

                            val splitAssets = selectedSplits.map { split ->

                                ApkAsset(

                                    url = split.downloadUrl,

                                    sha256 = null,

                                    fileName = "${split.name}.apk",

                                    sizeBytes = split.sizeBytes

                                )

                            }



                            val assets = listOf(baseAsset) + splitAssets

                            return Resolution.Available(

                                AppRelease(

                                    packageName = app.packageName,

                                    versionCode = apk.versionCode,

                                    versionName = apk.versionName,

                                    minSdk = apk.minSdk,

                                    assets = assets,

                                    expectedSigners = trustedSigners,

                                    origin = SourceKind.APTOIDE,

                                    originLabel = "Aptoide (fuente de terceros)"

                                )

                            )

                        }

                    }

                }

            }



            BuiltInSource.MozillaArchive -> {
                val url = snapshot.resolvedUrls[app.packageName]
                if (url != null) {
                    // Certificado de Firefox estable org.mozilla.firefox.
                    // No confundir con la firma de otros canales de Fenix.
                    val mozillaSigners = setOf(FirefoxArchiveSource.RELEASE_SIGNER_SHA256)
                    return directResolution(app, url, "Mozilla", snapshot, pins + mozillaSigners)
                }
                val failure = snapshot.probes[app.packageName]?.exceptionOrNull()
                if (failure != null && bestCatalog == null && directEntry == null) {
                    return Resolution.Failed("Mozilla: " + (failure.message ?: "archivo no disponible"))
                }
            }

            BuiltInSource.EnterpriseOnly -> Unit

        }



        if (bestCatalog != null) return Resolution.Available(bestCatalog.release)

        if (directEntry != null) return directResolution(app, directEntry.url, "Empresa", snapshot, pins + directEntry.signers)

        if (catalogReleases.isNotEmpty()) {

            return Resolution.Available(catalogReleases.maxBy { it.release.versionCode ?: 0L }.release) // se marcará incompatible

        }



        return Resolution.NoSource(noSourceReason(app, snapshot.catalog))

    }



    private fun directResolution(app: ManagedApp, url: String, publisher: String, snapshot: SourcesSnapshot, pins: Set<String>): Resolution {

        val probe = snapshot.probes[app.packageName]

        if (probe != null && probe.isFailure) {

            val error = probe.exceptionOrNull()

            return Resolution.Failed("$publisher: ${error?.message ?: "descarga oficial no disponible"}")

        }



        return Resolution.Available(directRepository.release(app, url, publisher, probe?.getOrNull(), pins))

    }



    private fun noSourceReason(app: ManagedApp, catalog: CatalogState): String = when (catalog) {

        CatalogState.NotConfigured -> when (app.builtInSource) {

            BuiltInSource.EnterpriseOnly -> "Sin fuente pública verificada: añádela al catálogo empresarial o importa la APK"

            // El propio Updater: su APK nueva llega por el catálogo empresarial o con un Installer más reciente.

            BuiltInSource.Embedded -> "Sus nuevas versiones llegan por el catálogo empresarial o con un Installer más reciente"

            else -> "Fuente no disponible"

        }



        is CatalogState.Failed -> catalog.message

        is CatalogState.Loaded -> "El catálogo empresarial no incluye esta aplicación"

    }



}
