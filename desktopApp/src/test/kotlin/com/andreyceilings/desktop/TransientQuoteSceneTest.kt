package com.andreyceilings.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.db.MeasurementPricingFixture
import com.andreyceilings.shared.db.PriceCatalogSchema
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.estimate.EstimateNumbering
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.objects.ObjectsClientsRows
import com.andreyceilings.shared.objects.ObjectsClientsScreen
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TransientQuoteSceneTest {
    @Test fun realScreenHasGuardedInputsAndNoSaveInBothThemes() {
        for (dark in listOf(false, true)) {
            val presenter = TransientQuoteFixture.presenter()
            val scene = scene(presenter, dark)
            try {
                scene.frame()
                assertTrue(scene.text().contains("Попередній кошторис"))
                assertTrue(scene.text().contains("28,00 грн"))
                assertFalse(scene.text().contains("Зберегти"))
                scene.reveal { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "12" }
                val field = scene.nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "12" }
                assertTrue(field.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("0.")) == true)
                scene.frame()
                assertNull(presenter.state.value.amounts)
                assertTrue(scene.text().contains("Сума недоступна"))
                assertFalse(scene.text().contains("28,00 грн"))
                scene.constraints = Constraints.fixed(280, 480); scene.frame()
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsActions.ScrollBy) != null })
                scene.constraints = Constraints.fixed(900, 1000); scene.frame()
                presenter.resetQuantity("c"); scene.frame()
                assertEquals(2800, presenter.state.value.amounts?.finalCents)
            } finally { scene.close() }
        }
    }

    @Test fun bottomHandoffAndReverseScrollExposeOneAccessibleFinalAmount() {
        for (dark in listOf(false, true)) for (count in listOf(1, 50)) {
            val presenter = TransientQuoteFixture.presenter(TransientQuoteFixture.loaded(count))
            val scene = scene(presenter, dark)
            try {
                scene.frame()
                val amount = measurementMoney(presenter.state.value.amounts!!.finalCents)
                assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == amount } })
                scene.reveal { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "Підсумок" } }
                scene.frame()
                assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == amount } })
                scene.scroll(-100000f)
                scene.await { scene.text().contains(amount) }
                assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == amount } })
                presenter.acceptRead(presenter.beginRead(), PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE))
                scene.frame(); assertFalse(scene.text().contains(amount))
            } finally { scene.close() }
        }
    }

    @Test fun saveFooterPreservesSingleAccessibleAmountAtEndOfList() {
        for (dark in listOf(false, true)) {
            val presenter = TransientQuoteFixture.presenter()
            val scene = scene(presenter, dark, footer = true)
            try {
                scene.frame()
                scene.reveal { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "Зберегти кошторис" } }
                val amount = measurementMoney(presenter.state.value.amounts!!.finalCents)
                assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == amount } })
                scene.scroll(-100000f); scene.frame()
                assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == amount } })
            } finally { scene.close() }
        }
    }

    @Test fun pendingSaveDisablesFinancialControlsAndBackUntilFailure() {
        val presenter = TransientQuoteFixture.presenter()
        presenter.select("element:o0", false)
        presenter.acknowledge(true)
        presenter.editTitle("Captured")
        val captured = presenter.state.value
        assertNotNull(presenter.beginSave(1))
        val scene = scene(presenter, false)
        fun disabled(node: SemanticsNode) = generateSequence(node) { it.parent }.any {
            it.config.contains(SemanticsProperties.Disabled)
        }
        try {
            scene.frame()
            val back = scene.nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "До заміру" } }
            assertTrue(disabled(back))
            scene.reveal { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "12" }
            assertTrue(disabled(scene.nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "12" }))
            scene.reveal { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Підтверджую цей перелік неврахованих робіт") }
            assertTrue(disabled(scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Підтверджую цей перелік неврахованих робіт") }))
            scene.reveal { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "0" }
            val discount = scene.nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "0" }
            assertTrue(disabled(discount))
            presenter.editDiscount("50"); presenter.select("element:d", true)
            assertEquals(captured, presenter.state.value)
            presenter.saveFailed("Retry")
            scene.frame()
            val unlocked = scene.nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "0" }
            assertFalse(disabled(unlocked))
            assertTrue(unlocked.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("20")) == true)
            scene.frame()
            assertEquals("20", presenter.state.value.discountText)
        } finally { scene.close() }
    }

    @Test fun projectConsolidatedPreviewRequiresExplicitRevisionAndHasNoDesktopExportButtons() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val room = MeasurementPricingFixture.seed(db, roomName = "Вітальня")
            val presenter = TransientQuotePresenter(room.measurementUuid)
            val store = ObjectsClientsStore(db) { 700 }
            presenter.acceptRead(presenter.beginRead(), LivePricingReader(db).read(room.projectId, room.measurementUuid))
            val saved = ImmutableEstimateRepository(db).saveIndividual(
                assertNotNull(presenter.saveRequest(room.projectId, "Кошторис вітальні")), 2026, 600
            )
            val publicNumber = EstimateNumbering.printable(saved.documentNumber)
            val scene = ImageComposeScene(640, 750) {
                CeilingsTheme(false) {
                    ObjectsClientsScreen(
                        rows = ObjectsClientsRows(emptyList(), emptyList()),
                        detailsProjectId = room.projectId, detailsStore = store,
                        onOpenProject = {}, onCloseProject = {}, databaseAvailable = true,
                        onSaveClient = { _, _ -> }, onSaveProject = { _, _ -> },
                        onDeleteClient = {}, onDeleteProject = {}, onDial = {}, onExternalAction = { _, _ -> }
                    )
                }
            }
            fun find(text: String) = scene.nodes().lastOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(text) }
            }
            fun click(text: String) {
                val node = assertNotNull(find(text), text)
                val action = generateSequence(node) { it.parent }
                    .firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }
                assertTrue(action?.invoke() == true, text)
                scene.frame()
            }
            val preview = "Переглянути зведений PDF"
            try {
                scene.await { find("Зведений кошторис") != null }
                click("Зведений кошторис")
                scene.await { find("Вітальня") != null }
                assertTrue(SemanticsProperties.Disabled in assertNotNull(find(preview)).config)
                click("Вітальня")
                assertTrue(SemanticsProperties.Disabled in assertNotNull(find(preview)).config)
                val option = assertNotNull(find("$publicNumber · v1"))
                val choice = assertNotNull(generateSequence(option) { it.parent }.firstOrNull {
                    it.config.getOrNull(SemanticsProperties.Selected) == false
                })
                assertTrue(choice.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
                scene.await { find(preview)?.config?.contains(SemanticsProperties.Disabled) == false }
                click(preview)
                scene.await { find("Збереження, надсилання й друк PDF доступні на Android.") != null }
                assertNull(find("Зберегти PDF"))
                assertNull(find("Поділитися"))
                assertNull(find("Друк"))
                fun pageDescriptions() = scene.nodes().flatMap { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }
                val firstPage = pageDescriptions().firstOrNull { "Зведений кошторис" in it }
                assertNotNull(firstPage, pageDescriptions().joinToString("\n"))
                assertTrue(publicNumber in firstPage, firstPage)
                val scroll = assertNotNull(scene.nodes().lastOrNull {
                    it.config.getOrNull(SemanticsActions.ScrollBy) != null
                }?.config?.getOrNull(SemanticsActions.ScrollBy)?.action)
                assertTrue(scroll(0f, 5000f))
                scene.await { pageDescriptions().any { "Загалом до оплати" in it } }
                click("Назад")
                scene.await { find(preview)?.config?.contains(SemanticsProperties.Disabled) == false }
                click("Скасувати")
                click("Зведений кошторис")
                scene.await { find(preview) != null }
                assertTrue(SemanticsProperties.Disabled in assertNotNull(find(preview)).config)
            } finally {
                scene.close()
                runBlocking { store.measurementDetails.elements.closeAndAwaitWrites() }
            }
        }
    }

    private fun scene(presenter: TransientQuotePresenter, dark: Boolean, footer: Boolean = false) = ImageComposeScene(600, 900) {
        CeilingsTheme(dark) {
            val state by presenter.state.collectAsState()
            val save by presenter.saveState.collectAsState()
            MeasurementQuoteContent(state, {}, {}, presenter::select, presenter::editQuantity, presenter::confirmQuantity,
                presenter::resetQuantity, presenter::editDiscount, presenter::confirmDiscount, presenter::acknowledge,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 84.dp),
                editingEnabled = !save.saving,
                saveFooter = if (footer) ({
                    androidx.compose.material3.OutlinedTextField("Кухня", {}, label = { androidx.compose.material3.Text("Назва кошторису") })
                    androidx.compose.material3.Button(onClick = {}) { androidx.compose.material3.Text("Зберегти кошторис") }
                }) else null)
        }
    }
    private var time = 0L
    private fun ImageComposeScene.frame() {
        repeat(3) {
            time += 250_000_000
            Snapshot.sendApplyNotifications(); render(time).close()
            // Scroll semantics and StateFlow collection dispatch work between rendered frames.
            Thread.sleep(10)
        }
    }
    private fun ImageComposeScene.await(predicate: () -> Boolean) {
        repeat(30) { frame(); if (predicate()) return }
        fail("Quote state did not settle after rendering queued frames")
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun ImageComposeScene.text() = nodes().joinToString { it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { t -> t.text } }
    private fun ImageComposeScene.scroll(delta: Float) {
        assertTrue(nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            .config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, delta) == true)
    }
    private fun ImageComposeScene.reveal(predicate: (SemanticsNode) -> Boolean) {
        repeat(100) { frame(); if (nodes().any(predicate)) return; scroll(700f) }
        fail("Quote control did not enter the viewport")
    }
}
