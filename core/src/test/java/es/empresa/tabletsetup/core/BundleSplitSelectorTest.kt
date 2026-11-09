package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.apk.BundleSplitSelector
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.ValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BundleSplitSelectorTest {
    private val arm = DeviceProfile(34, "14", listOf("arm64-v8a", "armeabi-v7a"),
        densityDpi = 420, preferredLocales = listOf("es-ES", "en-US"))

    @Test fun apkmSeleccionaBaseYConfiguracionesCorrectas() {
        val all = listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.x86_64.apk",
            "split_config.xhdpi.apk", "split_config.xxhdpi.apk",
            "split_config.es.apk", "split_config.en.apk")
        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk",
            "split_config.xxhdpi.apk", "split_config.es.apk"),
            BundleSplitSelector.select(all, arm))
    }

    @Test fun rechazaApkmIncompatibleConProcesador() {
        try {
            BundleSplitSelector.select(listOf("base.apk", "split_config.arm64_v8a.apk",
                "split_config.armeabi_v7a.apk"), arm.copy(supportedAbis = listOf("x86_64")))
            fail("Debía rechazar splits ARM en x86_64")
        } catch (e: ValidationException) {
            assertTrue(e.message.orEmpty().contains("arquitectura"))
        }
    }

    @Test fun conservaModulosYSeleccionaConfiguracionDelModulo() {
        val all = listOf("base.apk", "split_feature.apk",
            "split_feature.config.arm64_v8a.apk", "split_feature.config.x86_64.apk")
        assertEquals(listOf("base.apk", "split_feature.apk", "split_feature.config.arm64_v8a.apk"),
            BundleSplitSelector.select(all, arm))
    }

    @Test fun admiteApkUnicaYSplittedConBaseMaster() {
        assertEquals(listOf("waze.apk"), BundleSplitSelector.select(listOf("waze.apk"), arm))
        val all = listOf("base-master.apk", "config.arm64_v8a.apk", "config.x86_64.apk")
        assertEquals(listOf("base-master.apk", "config.arm64_v8a.apk"),
            BundleSplitSelector.select(all, arm))
    }

    @Test fun rechazaFaltaDeBase() {
        try {
            BundleSplitSelector.select(listOf("split_config.arm64_v8a.apk",
                "split_config.xxhdpi.apk"), arm)
            fail("Faltaba APK base")
        } catch (e: ValidationException) {
            assertTrue(e.message.orEmpty().contains("APK base"))
        }
    }
}
