package com.andreyceilings.shared.db

import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** The same CMP presentation -> production SQL proof runs with each platform's encrypted driver. */
object MeasurementElementsPresentationProof {
    suspend fun run(db: PrototypeDatabase) {
        val projects = ProjectRepository(db)
        val measurements = MeasurementRepository(db)
        val repo = MeasurementElementRepository(db)
        val prices = PriceRepository(db)
        val groupRepo = PriceGroupRepository(db)
        val p = projects.createReturningId(null, "P3B project", createdAt = 500)
        val p2 = projects.createReturningId(null, "P3B other", createdAt = 500)
        fun route(project: Long, name: String): ElementRoute {
            val id = measurements.createReturningId(project, name, createdAt = 501)
            return ElementRoute(project, id, checkNotNull(measurements.find(id)).uuid)
        }
        val first = route(p, "Kitchen"); val sibling = route(p, "Bedroom"); val other = route(p2, "Other")
        val geometry = measurements.find(first.measurementId)
        val group = groupRepo.create("P3B порожня категорія", 501)
        val itemGroup = groupRepo.create("P3B елементи", 501)
        prices.createInGroup("p3b.spot", "P3B Світильники", itemGroup, 77.0, 501, "шт.", QuantityMode.MANUAL)
        val store = MeasurementElementsStore(db, { 502 }, Dispatchers.Unconfined)
        try {
            val model = store.model(first)
            suspend fun loaded(count: Int) = withTimeout(10_000) {
                model.state.first { it is MeasurementElementsState.Loaded && it.items.size == count } as MeasurementElementsState.Loaded
            }
            suspend fun saved() = withTimeout(10_000) { model.editor.first { it == null } }
            loaded(0)
            assertSame(model, store.model(first), "Activity recreation reattaches to session memory")
            val catalog = withTimeout(10_000) { model.catalog.first { it is ElementCatalogState.Loaded } } as ElementCatalogState.Loaded
            assertTrue(catalog.groups.any { it.uuid == group })
            assertTrue(catalog.filtered("", group).isEmpty())
            val spot = catalog.items.first { it.code == "p3b.spot" }
            assertTrue(catalog.filtered(spot.title.uppercase(), null).any { it.uuid == spot.uuid })
            assertTrue(catalog.filtered("неіснуюча позиція P3B", null).isEmpty())
            model.add(); model.choose(ElementEditorAction.CREATE_WISH)
            model.update { it.copy(label = "Карниз уточнити", note = "Private fixture") }
            val uuid = checkNotNull(model.editor.value).draft.uuid
            model.requestDismiss(); assertTrue(checkNotNull(model.editor.value).exitRequested)
            model.continueEditing(); model.save(); model.save(); saved()
            var element = loaded(1).items.single()
            assertEquals(uuid, element.uuid); assertNull(element.unit); assertEquals(ElementQuantity.Unknown, element.quantity)
            model.open(element)
            model.update { it.copy(unit = "м.п.", input = ElementQuantityInput(ElementQuantityKind.EXACT, "2,5"), approval = ElementApproval.APPROVED) }
            model.save(); saved()
            element = checkNotNull(repo.find(uuid))
            assertEquals(ElementQuantity.Exact(2500), element.quantity)
            assertEquals("Private fixture", element.note)
            model.open(element); model.update { it.copy(unit = "", input = ElementQuantityInput()) }; model.save(); saved()
            element = checkNotNull(repo.find(uuid)); assertNull(element.unit)
            model.open(element); model.update { it.copy(unit = "шт.", input = ElementQuantityInput(ElementQuantityKind.RANGE, minimum = "4", maximum = "6")) }
            model.save(); saved(); element = checkNotNull(repo.find(uuid))
            assertEquals(ElementQuantity.Range(4000, 6000), element.quantity)
            // CAS retains the local draft until explicit reload/discard.
            model.open(element); model.update { it.copy(note = "Local draft") }
            val concurrent = repo.edit(element.version, "Other writer", element.approval, element.quantity, 503)
            model.save()
            withTimeout(10_000) { model.editor.first { it?.conflict == true } }
            assertEquals("Local draft", model.editor.value?.draft?.note)
            assertEquals("Other writer", repo.find(uuid)?.note)
            model.requestReload(); assertTrue(checkNotNull(model.editor.value).reloadRequested)
            model.reload()
            withTimeout(10_000) { model.editor.first { it?.draft?.original?.version == concurrent.version && !it.saving } }
            assertEquals("Other writer", model.editor.value?.draft?.note)
            model.requestDismiss()
            model.open(concurrent, ElementEditorAction.LINK); model.select(spot)
            assertTrue(checkNotNull(model.editor.value).draft.compatibleKeep)
            model.save(); saved()
            element = checkNotNull(repo.find(uuid)); assertIs<ElementSource.CatalogSelection>(element.source)
            assertEquals(ElementQuantity.Range(4000, 6000), element.quantity)
            assertEquals("Other writer", element.note)
            val length = catalog.items.first { it.mode == QuantityMode.ENTERED_LENGTH }
            val wish = repo.createWish(first.measurementUuid, "Pieces to length", 503, unit = "шт.", quantity = ElementQuantity.Exact(6000))
            loaded(2)
            model.open(wish, ElementEditorAction.LINK); model.select(length); model.save()
            assertEquals(wish, repo.find(wish.uuid)); assertNotNull(model.editor.value?.draft?.validationError)
            model.update { it.copy(replaceQuantity = true, input = ElementQuantityInput(ElementQuantityKind.EXACT, "2.5")) }
            model.save(); saved(); assertEquals(ElementQuantity.Exact(2500), repo.find(wish.uuid)?.quantity)
            for (item in catalog.items.filter { it.mode in listOf(QuantityMode.CEILING_AREA, QuantityMode.CEILING_PERIMETER) }) {
                model.add(); model.choose(ElementEditorAction.CREATE_CATALOG); model.select(item); model.save(); saved()
                val selected = repo.active().single { (it.source as? ElementSource.CatalogSelection)?.priceItemUuid == item.uuid && it.measurementUuid == first.measurementUuid }
                assertEquals(if (item.mode == QuantityMode.CEILING_AREA) ElementQuantity.CeilingArea else ElementQuantity.CeilingPerimeter, selected.quantity)
            }
            val row = checkNotNull(prices.all().find { it.uuid == spot.uuid })
            prices.delete(row.id)
            withTimeout(10_000) { model.state.first { state -> state is MeasurementElementsState.Loaded && state.items.any {
                it.uuid == uuid && (it.source as? ElementSource.CatalogSelection)?.availability == CatalogAvailability.DELETED_OR_MISSING } } }
            element = checkNotNull(repo.find(uuid))
            model.open(element); model.update { it.copy(note = "Edit unavailable snapshot") }; model.save(); saved()
            assertEquals(spot.title, repo.find(uuid)?.label)
            element = checkNotNull(repo.find(uuid)); model.open(element, ElementEditorAction.DELETE); model.save(); model.save(); saved()
            assertNotNull(repo.find(uuid)?.deletedAt)
            assertEquals(geometry, measurements.find(first.measurementId))
            val siblingModel = store.model(sibling)
            assertTrue((withTimeout(10_000) { siblingModel.state.first { it is MeasurementElementsState.Loaded } } as MeasurementElementsState.Loaded).items.isEmpty())
            assertNull(siblingModel.editor.value); assertNull(model.editor.value)
            val otherModel = store.model(other)
            assertTrue((withTimeout(10_000) { otherModel.state.first { it is MeasurementElementsState.Loaded } } as MeasurementElementsState.Loaded).items.isEmpty())
            projects.delete(p2)
            withTimeout(10_000) { otherModel.state.first { it == MeasurementElementsState.Missing } }
            otherModel.add(); assertNull(otherModel.editor.value)
            store.close(); assertEquals(MeasurementElementsState.Unavailable, otherModel.state.value)
        } finally { store.close() }
        val reopened = MeasurementElementsStore(db, { 504 }, Dispatchers.Unconfined)
        try {
            val actual = withTimeout(10_000) { reopened.model(first).state.first { it is MeasurementElementsState.Loaded } } as MeasurementElementsState.Loaded
            assertEquals(repo.active().filter { it.measurementUuid == first.measurementUuid }, actual.items)
        } finally { reopened.close() }
    }
}
