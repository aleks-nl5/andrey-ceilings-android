package com.andreyceilings.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedFoundationTest {
    @Test
    fun sharedModuleHasExpectedIdentity() {
        assertEquals("com.andreyceilings.shared", SharedModuleIdentity.packageName)
    }
}
