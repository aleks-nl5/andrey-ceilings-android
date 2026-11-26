package com.andreyceilings.shared.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.livepricing.QuoteDiscountMode
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/** Consistent compact discount-mode control in individual and consolidated estimates. */
@Composable
internal fun QuoteDiscountModeButton(
    mode: QuoteDiscountMode, enabled: Boolean, onChange: (QuoteDiscountMode) -> Unit
) {
    OutlinedButton(enabled = enabled, onClick = {
        onChange(if (mode == QuoteDiscountMode.PERCENT) QuoteDiscountMode.FIXED else QuoteDiscountMode.PERCENT)
    }, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(stringResource(if (mode == QuoteDiscountMode.PERCENT)
            Res.string.p5d_discount_percent else Res.string.p5d_discount_money))
    }
}
