package com.andreyceilings.shared.measurement

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.andreyceilings.shared.resources.*
import com.andreyceilings.shared.ui.ContextFeedbackIcon
import com.andreyceilings.shared.ui.ContextFeedbackKind
import org.jetbrains.compose.resources.stringResource

/** Uses the workspace's existing Save explanation anchor and contextual menu. */
@Composable
internal fun MeasurementValidationFeedback(
    label: String, messages: List<String>, expanded: Boolean,
    onExpanded: (Boolean) -> Unit, tint: Color
) {
    ContextFeedbackIcon(messages.joinToString("\n"), label, ContextFeedbackKind.ERROR,
        modifier = Modifier.testTag("measurement-edit-save-block-reason").semantics {
            liveRegion = LiveRegionMode.Polite
        }, expanded = expanded, onExpanded = onExpanded, tint = tint)
}

@Composable
internal fun MeasurementContourIssue.displayMessage(draft: MeasurementCreateDraft): String = when (code) {
    MeasurementContourIssueCode.MISSING -> stringResource(Res.string.p5c_missing_dimensions)
    MeasurementContourIssueCode.LENGTH -> stringResource(Res.string.p5c_length_invalid)
    MeasurementContourIssueCode.PRECISION -> stringResource(Res.string.p5c_length_precision,
        stringResource(Res.string.p5c_metres, formatPointMetres((draft.precision.stepMeters * 1000).toLong())))
    MeasurementContourIssueCode.RECTANGLE_MINIMUM -> stringResource(Res.string.p5c_rectangle_minimum)
    MeasurementContourIssueCode.CUTOUT_LENGTH -> stringResource(Res.string.p5c_cutout_length)
    MeasurementContourIssueCode.CUTOUT_WIDTH -> stringResource(Res.string.p5c_cutout_width)
    MeasurementContourIssueCode.L_MINIMUM -> stringResource(Res.string.p5c_l_minimum)
    MeasurementContourIssueCode.OPEN -> stringResource(Res.string.p5c_contour_open)
    MeasurementContourIssueCode.OVERLAP -> stringResource(Res.string.p5c_contour_overlap)
    MeasurementContourIssueCode.INTERSECTION -> stringResource(Res.string.p5c_contour_intersection)
    MeasurementContourIssueCode.INVALID -> stringResource(Res.string.p5b_save_invalid_contour)
}
