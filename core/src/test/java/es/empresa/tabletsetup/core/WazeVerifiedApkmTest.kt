package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.SourceKind
import es.empresa.tabletsetup.core.sources.WazeVerifiedApkm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeVerifiedApkmTest {
    @Test fun android9SeOmiteAunqueSeaArm() {
        assertNull(WazeVerifiedApkm.releaseFor(
            DeviceProfile(28, "9", listOf("arm64-v8a", "armeabi-v7a"))
        ))
    }

    @Test fun emuladorIntelNoAdmiteArchivoArm() {
        assertNull(WazeVerifiedApkm.releaseFor(
            DeviceProfile(34, "14", listOf("x86_64", "x86"))
        ))
    }

    @Test fun arm64DesdeAndroid10UsaElApkmOriginalVerificado() {
        for (sdk in listOf(29, 30, 34, 35)) {
            val release = WazeVerifiedApkm.releaseFor(
                DeviceProfile(sdk, "$sdk", listOf("arm64-v8a", "armeabi-v7a"))
            )!!
            assertEquals(AppCatalog.WAZE, release.packageName)
            assertEquals(1030744L, release.versionCode)
            assertEquals(SourceKind.VERIFIED_BUNDLE, release.origin)
            assertEquals(1, release.assets.size)
            assertTrue(release.assets.single().fileName.endsWith(".apkm"))
            assertEquals(WazeVerifiedApkm.SHA256, release.assets.single().sha256)
            assertEquals(setOf(WazeVerifiedApkm.SIGNER_SHA256), release.expectedSigners)
            assertTrue(release.assets.single().url.startsWith("https://github.com/"))
        }
    }

    @Test fun noConfundeArm32ConArm64() {
        assertNull(WazeVerifiedApkm.releaseFor(
            DeviceProfile(34, "14", listOf("armeabi-v7a"))
        ))
    }
}
