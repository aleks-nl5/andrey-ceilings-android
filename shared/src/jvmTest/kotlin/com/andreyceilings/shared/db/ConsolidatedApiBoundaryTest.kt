package com.andreyceilings.shared.db

import com.andreyceilings.shared.livepricing.*
import kotlin.test.*

/** Architectural boundary requested by the owner: consolidation must have no live-pricing input. */
class ConsolidatedApiBoundaryTest {
    @Test fun consolidatedEntrypointAcceptsOnlySelectedRoomsAndSavedIndividualRevisions() {
        val methods = ConsolidatedEstimateEngine::class.java.declaredMethods.filter { it.name == "buildPreview" }
        assertEquals(1, methods.size)
        assertEquals(listOf(ConsolidatedEstimateSelection::class.java, Map::class.java), methods.single().parameterTypes.toList())
        assertTrue(methods.single().genericParameterTypes[1].typeName.contains("FrozenIndividualRevision"))
        val fields = ConsolidatedEstimateSelection::class.java.declaredFields.map { it.type }
        assertFalse(fields.contains(LiveEstimatePreview::class.java))
        assertFalse(fields.contains(ActiveCatalogSnapshot::class.java))
    }
}
