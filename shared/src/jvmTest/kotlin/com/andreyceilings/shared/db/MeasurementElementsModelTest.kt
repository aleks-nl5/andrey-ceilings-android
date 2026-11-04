package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MeasurementElementsModelTest {
    @Test fun pausedWriteSurvivesReattachmentAndBlocksAllExitsAndDuplicateSave(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val p = ProjectRepository(db).createReturningId(null, "P3B slow", createdAt = 1)
            val id = MeasurementRepository(db).createReturningId(p, "Kitchen", createdAt = 2)
            val route = ElementRoute(p, id, checkNotNull(MeasurementRepository(db).find(id)).uuid)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val store = MeasurementElementsStore(db, {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); 3L
            }, Dispatchers.Unconfined)
            val model = store.model(route)
            val guard = MeasurementCreateNavigationGuard(); guard.bindElements(model)
            val nav = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(p).openMeasurement(p, id)
            try {
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } }
                model.add(); model.choose(ElementEditorAction.CREATE_WISH); model.update { it.copy(label = "One write") }
                val uuid = checkNotNull(model.editor.value).draft.uuid
                model.save()
                assertTrue(entered.await(10, TimeUnit.SECONDS)); assertTrue(checkNotNull(model.editor.value).saving)
                guard.unbindElements(model); assertTrue(guard.blocksExit(nav))
                assertSame(model, store.model(route)); guard.bindElements(model)
                for (destination in listOf(nav.back(), nav.selectTab(AppRoute.PRICES), nav.openMeasurement(p, id + 1)))
                    assertEquals(nav, guard.transition(nav, destination))
                var closed = false; guard.requestClose { closed = true }; assertFalse(closed)
                model.requestDismiss(); model.update { it.copy(label = "Cannot edit during save") }; model.save()
                assertEquals("One write", model.editor.value?.draft?.label)
                release.countDown()
                withTimeout(10_000) { model.editor.first { it == null } }
                assertEquals(listOf(uuid), MeasurementElementRepository(db).active().map { it.uuid })
                assertEquals(nav.back(), guard.transition(nav, nav.back()))
                model.add(); model.choose(ElementEditorAction.CREATE_WISH); model.update { it.copy(label = "Private draft") }
                store.close(); assertNull(model.editor.value); assertEquals(MeasurementElementsState.Unavailable, model.state.value)
                assertEquals(MeasurementElementsState.Unavailable, store.model(route).state.value)
                val newSession = MeasurementElementsStore(db, { 4 }, Dispatchers.Unconfined)
                try { assertNull(newSession.model(route).editor.value) } finally { newSession.close() }
            } finally { release.countDown(); store.close() }
        }
    }

    @Test fun unavailableCreateFreezesPayloadAndRetryReusesCallerUuid(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val p = ProjectRepository(db).createReturningId(null, "P3B retry", createdAt = 1)
            val id = MeasurementRepository(db).createReturningId(p, "Kitchen", createdAt = 2)
            val route = ElementRoute(p, id, checkNotNull(MeasurementRepository(db).find(id)).uuid)
            var unavailable = true
            val store = MeasurementElementsStore(db, {
                if (unavailable) throw IllegalStateException("Sensitive internal database detail")
                3L
            }, Dispatchers.Unconfined)
            try {
                val model = store.model(route)
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } }
                model.add(); model.choose(ElementEditorAction.CREATE_WISH); model.update { it.copy(label = "Retry same payload") }
                val uuid = checkNotNull(model.editor.value).draft.uuid
                model.save()
                withTimeout(10_000) { model.editor.first { it?.attemptedCreate == true } }
                assertFalse(checkNotNull(model.editor.value?.error).contains("Sensitive"))
                model.update { it.copy(label = "Different payload") }
                assertEquals("Retry same payload", model.editor.value?.draft?.label)
                assertEquals(uuid, model.editor.value?.draft?.uuid)
                unavailable = false; model.save(); model.save()
                withTimeout(10_000) { model.editor.first { it == null } }
                assertEquals(listOf(uuid), MeasurementElementRepository(db).active().map { it.uuid })
            } finally { store.close() }
        }
    }

    @Test fun deletedPickerSelectionRetainsDraftAndStaleDeleteCannotRemoveNewerRow(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val p = ProjectRepository(db).createReturningId(null, "P3B race", createdAt = 1)
            val id = MeasurementRepository(db).createReturningId(p, "Kitchen", createdAt = 2)
            val route = ElementRoute(p, id, checkNotNull(MeasurementRepository(db).find(id)).uuid)
            val store = MeasurementElementsStore(db, { 3 }, Dispatchers.Unconfined)
            try {
                val model = store.model(route)
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } }
                val catalog = withTimeout(10_000) { model.catalog.first { it is ElementCatalogState.Loaded } } as ElementCatalogState.Loaded
                val selected = catalog.items.first { it.code == "light.spot" }
                model.add(); model.choose(ElementEditorAction.CREATE_CATALOG); model.select(selected)
                val uuid = checkNotNull(model.editor.value).draft.uuid
                val prices = PriceRepository(db); val item = prices.all().single { it.uuid == selected.uuid }
                prices.updateConfigured(item, item.groupUuid, "Renamed while picking", item.unit,
                    com.andreyceilings.shared.prices.QuantityMode.MANUAL, com.andreyceilings.shared.prices.PlacementKind.POINT, item.price, 4)
                model.save()
                withTimeout(10_000) { model.editor.first { it?.error != null } }
                assertEquals(uuid, model.editor.value?.draft?.uuid)
                assertNull(MeasurementElementRepository(db).find(uuid))
                assertEquals(selected, model.editor.value?.draft?.selection)
                prices.delete(item.id)
                model.save(); withTimeout(10_000) { model.editor.first { it?.error != null && !it.saving } }
                assertNull(MeasurementElementRepository(db).find(uuid))
                model.requestDismiss(); model.discardAndExit()
                val repo = MeasurementElementRepository(db)
                val wish = repo.createWish(route.measurementUuid, "Stale delete", 4)
                model.open(wish, ElementEditorAction.DELETE)
                val newer = repo.edit(wish.version, "New note", wish.approval, wish.quantity, 5)
                model.save(); withTimeout(10_000) { model.editor.first { it?.conflict == true } }
                assertEquals(newer, repo.find(wish.uuid))
                assertNotNull(model.editor.value?.error)
            } finally { store.close() }
        }
    }
}
