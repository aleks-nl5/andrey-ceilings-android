package com.andreyceilings.shared.prices

/** Exact P1 contract: Unicode lowercase, no locale, composition or space folding. */
fun normalizedNameKey(raw: String): String = raw.trim().lowercase()

class DuplicateGroupName : IllegalStateException("Група з такою назвою вже існує")
class DuplicateItemTitle : IllegalStateException("Позиція з такою назвою вже існує")
