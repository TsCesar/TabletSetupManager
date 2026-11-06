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
}
