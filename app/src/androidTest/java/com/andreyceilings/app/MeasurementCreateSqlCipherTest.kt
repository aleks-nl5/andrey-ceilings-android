package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the common editor's production createReturningId path on actual SQLCipher/WAL. */
@RunWith(AndroidJUnit4::class)
class MeasurementCreateSqlCipherTest {
    @Test fun sharedEditCasPreservesCapturedGeometryAndRejectsConflicts(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "cmp-edit-sqlcipher.db"
        val key = ByteArray(32) { 0x58.toByte() }
        context.deleteDatabase(name)
        fun open() = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true)
        var project = 0L
        try {
            open().use { driver ->
                val db = PrototypeDatabase(driver)
                val projects = ProjectRepository(db); val rows = MeasurementRepository(db)
                project = projects.createReturningId(null, "Edit parent", createdAt = 1)
                val other = projects.createReturningId(null, "Other", createdAt = 2)
                val store = MeasurementEditStore(db) { 3 }
                for (precision in MeasurementPrecision.entries) for (shape in listOf("rectangle", "l_shaped", "polygon")) {
                    val id = MeasurementCreateStore(db) { 2 }.create(draft(project, shape, precision))
                    val before = rows.find(id)!!
                    val original = store.load(project, id)
                    assertEquals(before, rows.find(id))
                    val model = MeasurementEditModel(store, original)
                    when (shape) {
                        "rectangle" -> model.accept(MeasurementCreateIntent.Dimension("length", "6"), grouped = true)
                        "l_shaped" -> model.accept(MeasurementCreateIntent.Dimension("A", "6"), grouped = true)
                        else -> {
                            model.accept(MeasurementCreateIntent.SideLength(0, "6"), grouped = true)
                            model.accept(MeasurementCreateIntent.SideLength(2, "4"), grouped = true)
                        }
                    }
                    model.commitInput(); val changed = model.state.value.draft
                    model.undo(); assertEquals(original.draft, model.state.value.draft)
                    model.redo(); assertEquals(changed, model.state.value.draft)
                    coroutineScope { repeat(8) { launch { model.save() } } }
                    assertTrue(model.state.value.error, model.state.value.saved)
                    val after = rows.find(id)!!
                    assertEquals(before.uuid, after.uuid); assertEquals(before.createdAt, after.createdAt)
                    assertEquals(before.projectId, after.projectId); assertEquals(3L, after.updatedAt)
                    assertEquals(validateMeasurementCreate(changed).geometry, MeasurementGeometryCodec.read(after.drawingJson))
                    assertEquals(if (shape == "rectangle") 24.0 else 16.0, after.areaSquareMeters, 0.000001)
                    assertEquals(20.0, after.perimeterMeters, 0.000001)
                    val stale = MeasurementEditModel(store, original)
                    stale.accept(MeasurementCreateIntent.Notes("Stale")); stale.save()
                    assertTrue(stale.state.value.conflict); assertEquals(after, rows.find(id))
                    val restored = MeasurementEditModel.restore(store, MeasurementEditModel(store, store.load(project, id)).snapshot(), project, id)!!
                    assertFalse(restored.state.value.saved); assertEquals(after, rows.find(id))
                    val wrong = original.copy(draft = original.draft.copy(projectId = other))
                    try { store.update(wrong, wrong.draft.copy(name = "Foreign")); fail("Foreign ownership accepted") }
                    catch (_: MeasurementEditConflict) { }
                }
                val deletedId = MeasurementCreateStore(db) { 2 }.create(draft(other, "rectangle", MeasurementPrecision.ONE_CENTIMETER))
                val deleted = MeasurementEditModel(store, store.load(other, deletedId))
                deleted.accept(MeasurementCreateIntent.Name("Deleted")); rows.delete(deletedId); deleted.save()
                assertTrue(deleted.state.value.conflict)
                val orphanId = MeasurementCreateStore(db) { 2 }.create(draft(other, "rectangle", MeasurementPrecision.ONE_CENTIMETER))
                val orphan = MeasurementEditModel(store, store.load(other, orphanId))
                orphan.accept(MeasurementCreateIntent.Name("Orphan")); projects.delete(other); orphan.save()
                assertTrue(orphan.state.value.conflict)
            }
            open().use { driver ->
                val rows = MeasurementRepository(PrototypeDatabase(driver)).forProject(project)
                assertEquals(12, rows.size)
                rows.forEach { assertEquals(it.shape, MeasurementGeometryCodec.read(it.drawingJson).shapeType) }
            }
        } finally { context.deleteDatabase(name); key.fill(0) }
    }

    @Test fun editSaveSurvivesImmediateBackEscapeTabAndRecreation(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "cmp-edit-navigation.db"; val key = ByteArray(32) { 0x59.toByte() }
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true).use { driver ->
                val db = PrototypeDatabase(driver)
                val project = ProjectRepository(db).createReturningId(null, "Slow edit", createdAt = 1)
                val id = MeasurementCreateStore(db) { 2 }.create(draft(project, "rectangle", MeasurementPrecision.ONE_CENTIMETER))
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                val store = MeasurementEditStore(db) { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)); 3L }
                val model = MeasurementEditModel(store, store.load(project, id))
                model.accept(MeasurementCreateIntent.Dimension("length", "6"))
                val guard = MeasurementCreateNavigationGuard().also { it.bindEdit(model) }
                val route = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project).openMeasurement(project, id).editMeasurement(project, id)
                val write = launch(start = CoroutineStart.UNDISPATCHED) { model.save() }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS)); assertTrue(guard.blocksExit(route))
                    assertEquals(route, guard.transition(route, route.back()))
                    assertEquals(route, guard.transition(route, route.selectTab(AppRoute.PRICES)))
                    assertEquals(route, guard.transition(route, route.requestQuickCreate(AppQuickCreate.OBJECT)))
                    assertSame(model, MeasurementEditModel.restore(store, model.snapshot(), project, id))
                    model.save(); model.undo(); model.discardAndExit()
                } finally { release.countDown() }
                withTimeout(10_000) { write.join() }
                assertTrue(model.state.value.error, model.state.value.saved)
                assertEquals(route, guard.transition(route, route.back()))
                assertEquals(route.measurementSaved(project, id), guard.deliverEditResult(route, project, id))
                assertEquals(3L, MeasurementRepository(db).find(id)!!.updatedAt)
            }
        } finally { context.deleteDatabase(name); key.fill(0) }
    }

    @Test fun backAndTabCannotHideAnInFlightCipherTransaction(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "cmp-create-navigation.db"
        val key = ByteArray(32) { 0x57.toByte() }
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true).use { driver ->
                val db = PrototypeDatabase(driver)
                val project = ProjectRepository(db).createReturningId(null, "Slow Android parent", createdAt = 1)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val store = MeasurementCreateStore(db) {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    2L
                }
                val model = MeasurementCreateModel(store, draft(project, "rectangle", MeasurementPrecision.ONE_CENTIMETER))
                val guard = MeasurementCreateNavigationGuard()
                guard.bind(model)
                val route = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project).createMeasurement(project)
                val write = launch(start = CoroutineStart.UNDISPATCHED) { model.save() }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    assertTrue(guard.blocksExit(route))
                    assertEquals(route, guard.transition(route, route.back()))
                    assertEquals(route, guard.transition(route, route.selectTab(AppRoute.PRICES)))
                    assertEquals(route, guard.transition(route, route.requestQuickCreate(AppQuickCreate.OBJECT)))
                    assertFalse(model.cancel())
                    model.save()
                } finally { release.countDown() }
                withTimeout(10_000) { write.join() }
                val id = model.state.value.savedId!!
                assertEquals(route, guard.transition(route, route.back()))
                val details = guard.transition(route, route.measurementCreated(project, id))
                assertEquals(MeasurementDetails(project, id), details.measurementDetails)
                assertFalse(guard.blocksExit(details))
                assertEquals(id, MeasurementRepository(db).forProject(project).single().id)
                guard.unbind(model)
            }
        } finally { context.deleteDatabase(name); key.fill(0) }
    }

    @Test fun createEveryShapeAndPrecisionObserveReopenAndFailClosed(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "cmp-create-sqlcipher.db"
        val key = ByteArray(32) { 0x56.toByte() }
        context.deleteDatabase(name)
        fun open() = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true)
        var parent = 0L
        try {
            open().use { driver ->
                val database = PrototypeDatabase(driver)
                val projects = ProjectRepository(database)
                parent = projects.createReturningId(null, "Android CMP parent", createdAt = 1)
                val measurements = MeasurementRepository(database)
                val store = MeasurementCreateStore(database) { 1234 }
                val objects = ObjectsClientsStore(database) { 1235 }
                val changes = Channel<Int>(Channel.UNLIMITED)
                val observer = launch { objects.observeProjectDetails(parent).collect { changes.send(it.measurements.size) } }
                suspend fun awaitCount(expected: Int) = withTimeout(10_000) {
                    while (changes.receive() != expected) { }
                }
                try {
                    awaitCount(0)
                    var count = 0
                    for (precision in MeasurementPrecision.entries) for (shape in listOf("rectangle", "l_shaped", "polygon")) {
                        val draft = draft(parent, shape, precision)
                        val model = MeasurementCreateModel(store, draft)
                        coroutineScope { repeat(10) { launch { model.save() } } }
                        assertNotNull(model.state.value.savedId)
                        model.save()
                        awaitCount(++count)
                        assertEquals(count, measurements.forProject(parent).size)
                        val row = measurements.find(model.state.value.savedId!!)!!
                        assertEquals(parent, row.projectId)
                        assertEquals(1234L, row.createdAt)
                        assertEquals(row.createdAt, row.updatedAt)
                        assertTrue(row.uuid.isNotBlank())
                        assertEquals(validateMeasurementCreate(draft).geometry, MeasurementGeometryCodec.read(row.drawingJson))
                    }
                    val cancelled = MeasurementCreateModel(store, draft(parent, "rectangle", MeasurementPrecision.ONE_CENTIMETER))
                    assertTrue(cancelled.cancel())
                    cancelled.save()
                    assertNull(cancelled.state.value.savedId)
                    val deleted = projects.createReturningId(null, "Removed before Save", createdAt = 2)
                    val missing = MeasurementCreateModel(store, draft(deleted, "polygon", MeasurementPrecision.ONE_CENTIMETER))
                    projects.delete(deleted)
                    missing.save()
                    assertNull(missing.state.value.savedId)
                    assertNotNull(missing.state.value.error)
                    assertTrue(measurements.forProject(deleted).isEmpty())
                    val invalid = MeasurementCreateModel(store, draft(parent, "polygon", MeasurementPrecision.ONE_CENTIMETER)
                        .reduce(MeasurementCreateIntent.SideLength(0, "")))
                    invalid.save()
                    assertNull(invalid.state.value.savedId)
                    val restored = MeasurementCreateDraft.restore(draft(parent, "polygon", MeasurementPrecision.ONE_CENTIMETER).snapshot(), parent)!!
                    assertNull(MeasurementCreateModel(store, restored).state.value.savedId)
                    assertEquals(12, measurements.forProject(parent).size)
                } finally { observer.cancelAndJoin(); changes.close() }
            }
            val header = context.getDatabasePath(name).inputStream().use { it.readNBytes(16) }
            assertFalse(header.contentEquals("SQLite format 3\u0000".encodeToByteArray()))
            val reopened = open()
            val database = PrototypeDatabase(reopened)
            assertEquals(12, MeasurementRepository(database).forProject(parent).size)
            MeasurementRepository(database).forProject(parent).forEach { row ->
                assertEquals(row.shape, MeasurementGeometryCodec.read(row.drawingJson).shapeType)
                val details = MeasurementDetailsStore(database).observe(parent, row.id)
                    .first { it !is MeasurementDetailsState.Loading }
                assertTrue(details is MeasurementDetailsState.Loaded)
            }
            val closedStore = MeasurementCreateStore(database) { 2000 }
            reopened.close()
            val unavailable = MeasurementCreateModel(closedStore, draft(parent, "rectangle", MeasurementPrecision.ONE_CENTIMETER))
            unavailable.save()
            assertNull(unavailable.state.value.savedId)
            assertNotNull(unavailable.state.value.error)
            open().use { assertEquals(12, MeasurementRepository(PrototypeDatabase(it)).forProject(parent).size) }
        } finally { context.deleteDatabase(name); key.fill(0) }
    }

    private fun draft(parent: Long, shape: String, precision: MeasurementPrecision): MeasurementCreateDraft {
        val outline = when (shape) {
            "rectangle" -> MeasurementOutlineDraft.Template(shape, mapOf("length" to "5,1", "width" to "4"))
            "l_shaped" -> MeasurementOutlineDraft.Template(shape, mapOf("A" to "5", "B" to "4", "C" to "2", "D" to "2"))
            else -> MeasurementOutlineDraft.Orthogonal(listOf("5", "2", "3", "2", "2", "4").mapIndexed { i, value ->
                MeasurementSideInput(value, if (i == 2) OrthogonalTurn.LEFT else OrthogonalTurn.RIGHT)
            })
        }
        return MeasurementCreateDraft(parent, precision, "CMP $shape", "Примітки", outline)
    }
}
