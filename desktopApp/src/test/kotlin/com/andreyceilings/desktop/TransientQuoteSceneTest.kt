package com.andreyceilings.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.theme.CeilingsTheme
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
