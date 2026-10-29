package com.andreyceilings.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Minimal shared Compose component used to verify the CMP toolchain.
 *
 * This is not a replacement for any production Android screen. The existing
 * Android UI remains in :app until real screens are migrated and compared.
 */
@Composable
fun SharedFoundationStatus() {
    MaterialTheme {
        Surface {
            Text("Andrey Ceilings · shared UI foundation")
        }
    }
}
