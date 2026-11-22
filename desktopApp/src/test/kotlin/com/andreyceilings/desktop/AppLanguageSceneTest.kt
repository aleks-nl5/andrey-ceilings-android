package com.andreyceilings.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.localization.AppLanguage
import com.andreyceilings.shared.localization.AppLanguagePreference
import com.andreyceilings.shared.localization.AppLanguageResources
import com.andreyceilings.shared.localization.AppLanguageState
import com.andreyceilings.shared.localization.AppLanguageStorage
import com.andreyceilings.shared.navigation.AppDestination
import com.andreyceilings.shared.navigation.AppFloatingNavigation
import com.andreyceilings.shared.navigation.AppHeader
import com.andreyceilings.shared.objects.ObjectsClientsRows
import com.andreyceilings.shared.objects.ObjectsClientsScreen
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppLanguageSceneTest {
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun rootUsesUkrainianChromeSemanticsAndClientCountsWithAnUnsupportedSystemLocale() {
        val state = AppLanguageState(object : AppLanguageStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val dispatcher = DesktopSceneDispatcher()
        val scene = ImageComposeScene(540, 800, coroutineContext = dispatcher) {
            AppLanguageResources(state.preference, "ja-JP") {
                CeilingsTheme(darkTheme = false) {
                    Column {
                        AppHeader(0f, 0f, 0.dp, WindowInsets(0, 0, 0, 0), onSettings = {})
                        AppFloatingNavigation(
                            selectedId = "home",
                            destinations = listOf(AppDestination("home", "Головна", "Головна", Icons.Default.Home)),
                            darkTheme = false,
                            onSelect = {},
                            onQuickCreate = {}
                        )
                        ObjectsClientsScreen(
                            rows = ObjectsClientsRows(
                                projects = emptyList(),
                                clients = listOf(ClientListEntry(1, "Éclairage", "", "", "", 22))
                            ),
                            detailsProjectId = null,
                            detailsStore = null,
                            onOpenProject = {},
                            onCloseProject = {},
                            databaseAvailable = true,
                            onSaveClient = { _, _ -> },
                            onSaveProject = { _, _ -> },
                            onDeleteClient = {},
                            onDeleteProject = {},
                            onDial = {},
                            onExternalAction = { _, _ -> }
                        )
                    }
                }
            }
        }
        try {
            dispatcher.render(scene, 0)
            assertNotNull(scene.findText("Майстер натяжних стель"))
            assertTrue(scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Налаштування")
            })
            assertTrue(scene.nodes().any {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == "Перейти: Головна"
            })
            val clients = assertNotNull(scene.findText("Клієнти"))
            assertTrue(assertNotNull(clients.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            dispatcher.render(scene, 32_000_000)
            assertNotNull(scene.findText("22 об’єкти"))
            assertNotNull(scene.findText("Éclairage"))

            state.select(AppLanguagePreference.Explicit(AppLanguage.UKRAINIAN))
            Snapshot.sendApplyNotifications()
            dispatcher.render(scene, 64_000_000)
            assertNotNull(scene.findText("22 об’єкти"))
            assertNotNull(scene.findText("Éclairage"))
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.findText(text: String): SemanticsNode? = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
}
