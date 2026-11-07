package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
                model.add(); model.choose(ElementEditorAction.CREATE_CATALOG); model.select(selected); model.update { it.copy(businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK) }
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

    @Test fun concurrentPlacementChangeRequiresExplicitReselectionForCreateAndLink(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(null, "P3B placement", createdAt = 1)
            val measurement = MeasurementRepository(db).createReturningId(project, "Kitchen", createdAt = 2)
            val route = ElementRoute(project, measurement, checkNotNull(MeasurementRepository(db).find(measurement)).uuid)
            val repo = MeasurementElementRepository(db)
            val prices = PriceRepository(db)
            val store = MeasurementElementsStore(db, { 5 }, Dispatchers.Unconfined)
            try {
                val model = store.model(route)
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } }
                val catalog = withTimeout(10_000) { model.catalog.first { it is ElementCatalogState.Loaded } } as ElementCatalogState.Loaded
                val selected = catalog.items.first { it.code == "light.spot" }
                val before = prices.all().single { it.uuid == selected.uuid }
                val replacement = if (selected.placementKind == com.andreyceilings.shared.prices.PlacementKind.NONE)
                    com.andreyceilings.shared.prices.PlacementKind.POINT else com.andreyceilings.shared.prices.PlacementKind.NONE

                model.add(); model.choose(ElementEditorAction.CREATE_CATALOG); model.select(selected); model.update { it.copy(businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK) }
                val newUuid = checkNotNull(model.editor.value).draft.uuid
                prices.updateConfigured(before, before.groupUuid, before.title, before.unit, selected.mode,
                    replacement, before.price, 4)
                model.save()
                withTimeout(10_000) { model.editor.first { it?.error != null && !it.saving } }
                assertNull(repo.find(newUuid), "A changed placement snapshot must not be silently accepted")
                assertEquals(selected, model.editor.value?.draft?.selection)

                val updated = withTimeout(10_000) {
                    model.catalog.first { state -> state is ElementCatalogState.Loaded &&
                        state.items.any { it.uuid == selected.uuid && it.placementKind == replacement } }
                } as ElementCatalogState.Loaded
                val refreshed = updated.items.single { it.uuid == selected.uuid }
                model.select(refreshed); model.save()
                withTimeout(10_000) { model.editor.first { it == null } }
                val created = checkNotNull(repo.find(newUuid))
                assertEquals(replacement, (created.source as ElementSource.CatalogSelection).placementKind)

                val wish = repo.createWish(route.measurementUuid, "Unlinked wish", 6)
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded && it.items.size == 2 } }
                model.open(wish, ElementEditorAction.LINK); model.select(selected); model.update { it.copy(businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK) }
                model.save()
                withTimeout(10_000) { model.editor.first { it?.error != null && !it.saving } }
                assertEquals(wish, repo.find(wish.uuid))
                model.select(refreshed); model.save()
                withTimeout(10_000) { model.editor.first { it == null } }
                assertEquals(replacement, (checkNotNull(repo.find(wish.uuid)).source as ElementSource.CatalogSelection).placementKind)
            } finally { store.close() }
        }
    }

    @Test fun desktopSessionShutdownDrainsInFlightWriteBeforeDriverClose(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(null, "P3B shutdown", createdAt = 1)
            val measurement = MeasurementRepository(db).createReturningId(project, "Kitchen", createdAt = 2)
            val route = ElementRoute(project, measurement, checkNotNull(MeasurementRepository(db).find(measurement)).uuid)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val error = AtomicReference<Throwable?>(null)
            val store = MeasurementElementsStore(db, {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                3L
            }, Dispatchers.Unconfined)
            val closer = Thread {
                try { runBlocking { store.closeAndAwaitWrites() } }
                catch (failure: Throwable) { error.set(failure) }
                finally { finished.countDown() }
            }
            try {
                val model = store.model(route)
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } }
                model.add(); model.choose(ElementEditorAction.CREATE_WISH)
                model.update { it.copy(label = "Committed before encrypted driver closes") }
                val uuid = checkNotNull(model.editor.value).draft.uuid
                model.save()
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                closer.start()
                assertFalse(finished.await(250, TimeUnit.MILLISECONDS), "Shutdown overtook an in-flight write")
                release.countDown()
                assertTrue(finished.await(10, TimeUnit.SECONDS), "Shutdown did not drain the write")
                error.get()?.let { throw AssertionError("Shutdown failed", it) }
                assertEquals(listOf(uuid), MeasurementElementRepository(db).active().map { it.uuid })
                assertEquals(MeasurementElementsState.Unavailable, model.state.value)
                model.save() // A closed session must not initiate a late write.
                assertEquals(1, MeasurementElementRepository(db).active().size)
            } finally {
                release.countDown()
                closer.join(10_000)
                store.close()
            }
        }
    }
}
