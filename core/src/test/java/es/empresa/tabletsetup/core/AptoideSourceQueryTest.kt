package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.sources.AptoideSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AptoideSourceQueryTest {
    @Test
    fun `prueba filtros completos primero y reduce restricciones sin repetir consultas`() {
        assertEquals(
            listOf(
                "maxSdk=28&myCPU=x86_64,x86&myDensity=320",
                "maxSdk=28&myCPU=x86_64,x86",
                "maxSdk=28",
                ""
            ),
            AptoideSource.deviceQueryCandidates(28, listOf("x86_64", "x86"), 320)
        )
    }

    @Test
    fun `sin arquitectura ni densidad no repite variantes`() {
        assertEquals(
            listOf("maxSdk=34", ""),
            AptoideSource.deviceQueryCandidates(34, emptyList(), 0)
        )
    }
    @Test
    fun `descarta APK divididas ARM en tablet x86_64`() {
        assertEquals(false, AptoideSource.supportsAbiSplits(
            listOf("config.arm64_v8a", "config.armeabi_v7a"),
            listOf("x86_64", "x86")
        ))
    }

    @Test
    fun `acepta una variante ABI que si es compatible`() {
        assertEquals(true, AptoideSource.supportsAbiSplits(
            listOf("config.arm64_v8a", "config.x86_64"),
            listOf("x86_64")
        ))
        assertEquals(true, AptoideSource.supportsAbiSplits(
            listOf("config.arm64_v8a"),
            listOf("arm64-v8a", "armeabi-v7a")
        ))
    }

    @Test
    fun `sin splits ABI se comprueba despues al inspeccionar la APK`() {
        assertEquals(true, AptoideSource.supportsAbiSplits(emptyList(), listOf("x86_64")))
    }

    @Test
    fun versionesChromeAndroid9SeRestringenASerie138() {
        assertEquals(720_499_999L, AptoideSource.initialMaxVersionCode("com.android.chrome", 28))
        assertEquals(null, AptoideSource.initialMaxVersionCode("com.android.chrome", 34))
        assertEquals(66_019_790L, AptoideSource.initialMaxVersionCode("com.google.android.gm", 28))
        assertEquals(null, AptoideSource.initialMaxVersionCode("com.google.android.gm", 34))
    }

    @Test
    fun normalizaNombresDeArquitecturaDeAptoide() {
        assertEquals("x86_64", AptoideSource.normalizeAbi("x86-64"))
        assertEquals("x86_64", AptoideSource.normalizeAbi("X86_64"))
        assertEquals("armeabi-v7a", AptoideSource.normalizeAbi("arm-v7a"))
        assertEquals("arm64-v8a", AptoideSource.normalizeAbi("arm64-v8a"))
    }

    @Test
    fun fuentesWazeIncluyenApkNormalesDespuesDeAppBundles() {
        val all = AptoideSource.downloadQueryCandidates(
            "com.waze", 29, listOf("arm64-v8a", "armeabi-v7a"), 420
        )
        assertEquals(8, all.size)
        assertEquals(true, all.first().second)
        assertEquals(false, all.last().second)
        assertEquals(
            "maxSdk=29&myCPU=arm64-v8a,armeabi-v7a&myDensity=420",
            all.first().first
        )
        assertEquals(all.first().first, all[4].first)
    }

    @Test
    fun gmailYChromeBuscanApkMonoliticasComoUltimoRecurso() {
        for (pkg in listOf("com.android.chrome", "com.google.android.gm")) {
            val list = AptoideSource.downloadQueryCandidates(
                pkg, 28, listOf("x86_64", "x86"), 320
            )
            assertEquals(8, list.size)
            assertEquals(4, list.count { !it.second })
        }
    }

    @Test
    fun mapasConservaLaConsultaNormalSinCosteExtra() {
        val list = AptoideSource.downloadQueryCandidates(
            "com.google.android.apps.maps", 34, listOf("x86_64"), 320
        )
        assertEquals(4, list.size)
        assertEquals(true, list.all { it.second })
    }

}
