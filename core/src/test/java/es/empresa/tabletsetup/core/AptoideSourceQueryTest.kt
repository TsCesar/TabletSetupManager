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

}
