package es.empresa.tabletsetup.core.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import es.empresa.tabletsetup.core.BuildConfig
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.InstalledInfo
import es.empresa.tabletsetup.core.util.Hashing
import java.io.File

/** Información del dispositivo y de la red. */
object DeviceInfo {
    fun current(): DeviceProfile = DeviceProfile(
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE.orEmpty(),
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
    )

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/** Ajustes persistentes compartidos por la interfaz y el trabajo periódico. */
class SuiteSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tablet_suite_settings", Context.MODE_PRIVATE)

    var catalogUrl: String
        get() = prefs.getString(KEY_CATALOG_URL, null) ?: BuildConfig.DEFAULT_CATALOG_URL
        set(value) = prefs.edit().putString(KEY_CATALOG_URL, value.trim()).apply()

    var catalogPublicKey: String
        get() = prefs.getString(KEY_CATALOG_KEY, null) ?: BuildConfig.DEFAULT_CATALOG_PUBLIC_KEY
        set(value) = prefs.edit().putString(KEY_CATALOG_KEY, value.trim()).apply()

    var lastCatalogSequence: Long
        get() = prefs.getLong("catalog_sequence", 0L)
        set(value) = prefs.edit().putLong("catalog_sequence", value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.edit().putBoolean("notifications_enabled", value).apply()

    /** Intervalo aproximado de comprobación periódica (horas). */
    var checkIntervalHours: Int
        get() = prefs.getInt("check_interval_hours", DEFAULT_INTERVAL_HOURS)
        set(value) = prefs.edit().putInt("check_interval_hours", value).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(value) = prefs.edit().putBoolean("wifi_only", value).apply()

    var lastCheckAt: Long
        get() = prefs.getLong("last_check_at", 0L)
        set(value) = prefs.edit().putLong("last_check_at", value).apply()

    var lastCheckSummary: String
        get() = prefs.getString("last_check_summary", "").orEmpty()
        set(value) = prefs.edit().putString("last_check_summary", value).apply()

    var lastNotifiedSignature: String
        get() = prefs.getString("last_notified", "").orEmpty()
        set(value) = prefs.edit().putString("last_notified", value).apply()

    var updaterConfiguredAt: Long
        get() = prefs.getLong("updater_configured_at", 0L)
        set(value) = prefs.edit().putLong("updater_configured_at", value).apply()

    var remoteTestAt: Long
        get() = prefs.getLong("remote_test_at", 0L)
        set(value) = prefs.edit().putLong("remote_test_at", value).apply()

    var remoteTestOk: Boolean
        get() = prefs.getBoolean("remote_test_ok", false)
        set(value) = prefs.edit().putBoolean("remote_test_ok", value).apply()

    var quickSupportIdConfirmed: Boolean
        get() = prefs.getBoolean("qs_id_confirmed", false)
        set(value) = prefs.edit().putBoolean("qs_id_confirmed", value).apply()

    val catalogConfigured: Boolean get() = catalogUrl.isNotBlank()

    companion object {
        const val KEY_CATALOG_URL = "catalog_url"
        const val KEY_CATALOG_KEY = "catalog_public_key"
        const val DEFAULT_INTERVAL_HOURS = 24
        val INTERVAL_OPTIONS = listOf(6, 12, 24, 48, 72)
    }
}

/** Evento del historial básico de resultados. */
data class HistoryEvent(
    val time: Long,
    val packageName: String,
    val label: String,
    val action: String,
    val success: Boolean,
    val message: String,
)

/** Historial en un archivo JSON Lines privado (máximo [MAX] entradas). */
class HistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "historial.jsonl")

    @Synchronized
    fun append(event: HistoryEvent) {
        val json = JsonObject().apply {
            addProperty("t", event.time)
            addProperty("p", event.packageName)
            addProperty("l", event.label)
            addProperty("a", event.action)
            addProperty("ok", event.success)
            addProperty("m", event.message.take(400))
        }
        val lines = (if (file.exists()) file.readLines() else emptyList()) + json.toString()
        file.writeText(lines.takeLast(MAX).joinToString("\n") + "\n")
    }

    @Synchronized
    fun list(): List<HistoryEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            try {
                val o = JsonParser.parseString(line).asJsonObject
                HistoryEvent(
                    time = o.get("t").asLong,
                    packageName = o.get("p").asString,
                    label = o.get("l").asString,
                    action = o.get("a").asString,
                    success = o.get("ok").asBoolean,
                    message = o.get("m").asString,
                )
            } catch (e: Exception) {
                null
            }
        }.asReversed()
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        const val MAX = 500
    }
}

/** Consultas a PackageManager sobre aplicaciones instaladas. */
class PackageStateReader(context: Context) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager

    @SuppressLint("WrongConstant")
    private fun packageInfo(packageName: String, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun isInstalled(packageName: String): Boolean = packageInfo(packageName, 0) != null

    fun installed(packageName: String): InstalledInfo? {
        val info = packageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        val appInfo = info.applicationInfo
        val system = appInfo != null && (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        return InstalledInfo(
            versionCode = info.longVersionCode,
            versionName = info.versionName,
            isSystemApp = system,
            signers = signers(info),
            installerPackage = installerOf(packageName),
        )
    }

    fun ownSigners(): Set<String> =
        packageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)?.let { signers(it) }.orEmpty()

    private fun signers(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return certs.orEmpty().map { Hashing.sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun installerOf(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    } catch (e: Exception) {
        null
    }

    /** true si la app nunca se ha abierto (o se forzó su detención): no recibe trabajos ni alarmas. */
    fun isStopped(packageName: String): Boolean = try {
        val info = packageInfo(packageName, 0)?.applicationInfo
        info != null && (info.flags and ApplicationInfo.FLAG_STOPPED) != 0
    } catch (e: Exception) {
        false
    }

    fun signaturesMatchOwn(packageName: String): Boolean =
        pm.checkSignatures(appContext.packageName, packageName) == PackageManager.SIGNATURE_MATCH

    fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)

    fun icon(packageName: String): Drawable? = try {
        pm.getApplicationIcon(packageName)
    } catch (e: Exception) {
        null
    }

    fun canRequestInstalls(): Boolean = pm.canRequestPackageInstalls()
}
