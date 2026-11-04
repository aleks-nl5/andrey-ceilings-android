package com.andreyceilings.shared.prices

import kotlin.test.*

class NormalizedNameTest {
    @Test fun exactUnicodeContract() {
        com.andreyceilings.shared.db.PriceNameUniquenessProof.normalization()
    }
}
