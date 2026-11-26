package com.andreyceilings.shared.estimate

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.andreyceilings.shared.resources.*
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource

@Composable
fun EstimateNumberSettingsSection() {
    val examples = remember {
        val defaults = EstimateIssuancePolicy()
        val date = EstimateNumbering.issueDate(Clock.System.now().toEpochMilliseconds(), TimeZone.currentSystemDefault())
        EstimateType.entries.map { EstimateNumbering.render(defaults.resolve(it), date, 1) }
    }
    ListItem(
        headlineContent = { Text(stringResource(Res.string.estimate_numbering_title)) },
        supportingContent = {
            Column {
                Text(stringResource(Res.string.estimate_numbering_defaults, examples[0], examples[1]))
                Text(stringResource(Res.string.estimate_numbering_editor_deferred))
            }
        }
    )
}

@Composable
fun preV22EstimateRestoreWarning(): String = stringResource(Res.string.estimate_pre_v22_restore_warning)
