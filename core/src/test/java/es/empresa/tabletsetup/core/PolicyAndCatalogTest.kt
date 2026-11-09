package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.model.AppRelease
import es.empresa.tabletsetup.core.model.ApkAsset
import es.empresa.tabletsetup.core.model.BuiltInSource
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.RuleResult
import es.empresa.tabletsetup.core.model.SourceKind
import es.empresa.tabletsetup.core.policy.CompatibilityPolicy
import es.empresa.tabletsetup.core.policy.ExcludedApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyAndCatalogTest {
    private val android9 = DeviceProfile(28, "9", listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
    private val android10 = DeviceProfile(29, "10", listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
    private val android15 = DeviceProfile(35, "15", listOf("arm64-v8a"))

    private fun release(pkg: String, minSdk: Int? = 28, maxSdk: Int? = null, abis: Set<String> = emptySet()) = AppRelease(
        packageName = pkg, versionCode = 10, versionName = "1.0", minSdk = minSdk, maxSdk = maxSdk, abis = abis,
        assets = listOf(ApkAsset("https://example.invalid/a.apk", "0".repeat(64), "a.apk")),
        origin = SourceKind.ENTERPRISE_CATALOG,
    )

    @Test
    fun `Waze bloqueado en Android 9 con el mensaje exigido`() {
        val rule = CompatibilityPolicy.deviceRule(AppCatalog.WAZE, android9, 29)
        assertTrue(rule is RuleResult.Blocked)
        assertEquals("No compatible con Android 9", (rule as RuleResult.Blocked).reason)
        // También para una versión publicada concreta (descargas, APK locales y actualizaciones).
        val releaseRule = CompatibilityPolicy.releaseRule(release(AppCatalog.WAZE, minSdk = 21), android9)
        assertEquals("No compatible con Android 9", (releaseRule as RuleResult.Blocked).reason)
    }

    @Test
    fun `Waze en Android 10 o superior queda sujeto a la version concreta`() {
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.deviceRule(AppCatalog.WAZE, android10, 29))
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.releaseRule(release(AppCatalog.WAZE, minSdk = 29), android10))
        // Una versión que exige Android 11 no es compatible con Android 10.
        val blocked = CompatibilityPolicy.releaseRule(release(AppCatalog.WAZE, minSdk = 30), android10)
        assertTrue(blocked is RuleResult.Blocked)
        assertTrue((blocked as RuleResult.Blocked).reason.contains("Android 11"))
    }

    @Test
    fun `otras apps se permiten en Android 9`() {
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.deviceRule(AppCatalog.MAPS, android9))
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.releaseRule(release(AppCatalog.MAPS), android9))
    }

    @Test
    fun `rango de SDK y ABI de la version publicada`() {
        assertTrue(CompatibilityPolicy.releaseRule(release(AppCatalog.WHATSAPP, minSdk = 31), android10) is RuleResult.Blocked)
        assertTrue(CompatibilityPolicy.releaseRule(release(AppCatalog.WHATSAPP, maxSdk = 28), android10) is RuleResult.Blocked)
        assertTrue(CompatibilityPolicy.releaseRule(release(AppCatalog.WHATSAPP, abis = setOf("x86")), android10) is RuleResult.Blocked)
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.releaseRule(release(AppCatalog.WHATSAPP, abis = setOf("armeabi-v7a")), android10))
        // Tablet solo de 64 bits: una APK solo de 32 bits no es compatible.
        assertTrue(CompatibilityPolicy.releaseRule(release(AppCatalog.WHATSAPP, abis = setOf("armeabi-v7a")), android15) is RuleResult.Blocked)
        // Sin código nativo es compatible con cualquier arquitectura.
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.releaseRule(release(AppCatalog.WHATSAPP), android15))
    }

    @Test
    fun `comparacion de versiones y bajadas`() {
        assertTrue(CompatibilityPolicy.needsUpdate(null, 2))
        assertTrue(CompatibilityPolicy.needsUpdate(3, 4))
        assertFalse(CompatibilityPolicy.needsUpdate(4, 4))
        assertFalse(CompatibilityPolicy.needsUpdate(5, 4))
        assertFalse(CompatibilityPolicy.needsUpdate(5, null))
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.versionRule(null, 1))
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.versionRule(4, 5))
        assertTrue(CompatibilityPolicy.versionRule(5, 5) is RuleResult.Blocked)
        val downgrade = CompatibilityPolicy.versionRule(6, 5)
        assertTrue((downgrade as RuleResult.Blocked).reason.contains("Bajada de versión"))
    }

    @Test
    fun `targetSdk demasiado antiguo para Android 14 y 15`() {
        assertTrue(CompatibilityPolicy.targetSdkRule(22, 34) is RuleResult.Blocked)
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.targetSdkRule(23, 34))
        assertTrue(CompatibilityPolicy.targetSdkRule(23, 35) is RuleResult.Blocked)
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.targetSdkRule(22, 33))
    }

    @Test
    fun `FuelSoft excluido del catalogo y bloqueado siempre`() {
        assertTrue(AppCatalog.apps.none { it.label.contains("FuelSoft", ignoreCase = true) })
        assertTrue(AppCatalog.apps.none { it.packageName.contains("fuelsoft", ignoreCase = true) })
        assertTrue(ExcludedApps.isExcluded("com.empresa.fuelsoft"))
        assertTrue(ExcludedApps.isExcluded("com.ejemplo.app", "FuelSoft Gestión"))
        assertTrue(CompatibilityPolicy.deviceRule("com.empresa.fuelsoft.mobile", android10) is RuleResult.Blocked)
    }

    @Test
    fun `catalogo contiene las aplicaciones originales`() {
        val expected = setOf(
            AppCatalog.MUPDF, AppCatalog.QUICKSUPPORT, AppCatalog.QS_UNIVERSAL_ADDON, AppCatalog.CHROME,
            AppCatalog.MAPS, AppCatalog.WAZE, AppCatalog.WHATSAPP, AppCatalog.GMAIL, AppCatalog.UPDATER,
            AppCatalog.FIREFOX, AppCatalog.ADOBE_READER,
        )
        assertEquals(expected, AppCatalog.packageNames)
        assertTrue(AppCatalog.find(AppCatalog.QS_UNIVERSAL_ADDON)!!.optional)
        assertTrue(AppCatalog.find(AppCatalog.FIREFOX)!!.optional)
        assertTrue(AppCatalog.find(AppCatalog.ADOBE_READER)!!.optional)
        assertTrue(AppCatalog.find(AppCatalog.ADOBE_READER)!!.builtInSource is BuiltInSource.Aptoide)
        assertEquals(BuiltInSource.MozillaArchive, AppCatalog.find(AppCatalog.FIREFOX)!!.builtInSource)
        assertEquals(29, AppCatalog.find(AppCatalog.WAZE)!!.minDeviceSdk)
        assertTrue(AppCatalog.find(AppCatalog.WAZE)!!.builtInSource is BuiltInSource.Aptoide)
        assertEquals(BuiltInSource.FDroid, AppCatalog.find(AppCatalog.MUPDF)!!.builtInSource)
        val qs = AppCatalog.find(AppCatalog.QUICKSUPPORT)!!.builtInSource as BuiltInSource.OfficialDirect
        assertTrue(qs.url.startsWith("https://"))
        assertNotNull(AppCatalog.find(AppCatalog.UPDATER))
        // Las URL integradas son HTTPS.
        AppCatalog.apps.map { it.builtInSource }.filterIsInstance<BuiltInSource.OfficialDirect>()
            .forEach { assertTrue(it.url.startsWith("https://")) }
    }
}
