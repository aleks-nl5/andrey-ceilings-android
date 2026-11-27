package com.andreyceilings.shared.objects

/** Accept explicit international numbers only; never infer a country code. */
internal fun internationalMessengerDigits(raw: String): String? {
    val trimmed = raw.trim()
    val number = when {
        trimmed.startsWith("+") -> trimmed.drop(1)
        trimmed.startsWith("00") -> trimmed.drop(2)
        else -> return null
    }
    if (number.any { it !in '0'..'9' && !it.isWhitespace() && it !in "()-." }) return null
    return number.filter { it in '0'..'9' }.takeIf { it.length in 8..15 && it.first() != '0' }
}

/** Native app first, then an official HTTPS entry point where one exists. */
fun messengerLinks(action: ContactAction, raw: String): List<String> {
    val digits = internationalMessengerDigits(raw) ?: return emptyList()
    return when (action) {
        ContactAction.WHATSAPP -> listOf("whatsapp://send?phone=$digits", "https://wa.me/$digits")
        ContactAction.TELEGRAM -> listOf("tg://resolve?phone=$digits", "https://t.me/+$digits")
        ContactAction.VIBER -> listOf("viber://chat?number=%2B$digits")
        else -> emptyList()
    }
}

/** A failing native handler must not prevent the next supported candidate. */
fun tryOpenContactLinks(links: List<String>, open: (String) -> Unit): Boolean =
    links.any { uri ->
        try { open(uri); true } catch (_: Exception) { false }
    }
