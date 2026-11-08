package com.andreyceilings.shared.estimate

/**
 * Formatting of the human-facing number (e.g. KSH-2026-00001).
 *
 * The entire UUID v4 is ALWAYS persisted in the internal documentNumber,
 * even when the printable template does not contain {uuid}. The UUID is
 * never part of the printable number or a customer-facing PDF.
 */
@kotlinx.serialization.Serializable
data class EstimateNumberPolicy(
    val template: String = "{prefix}-{year}-{sequence}-{suffix}",
    val prefix: String = "AC",
    val sequenceWidth: Int = 5,
    val suffixLabel: String = ""
)

data class EstimateNumberContext(
    val seriesUuid: String,
    val year: Int,
    val localSequence: Long,
    val publicToken: String = ""
)

/** Pure shared KMP numbering on Android, Windows and iOS. */
object EstimateNumbering {
    private val placeholders = setOf("prefix", "year", "sequence", "uuid", "suffix")
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val uuidPart = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val templateToken = Regex("\\{([a-z]+)\\}")
    private val safePrefix = Regex("^[A-Za-zА-Яа-яІіЇїЄєҐґ0-9_-]{1,20}$")
    private val safeTemplate = Regex("^[A-Za-zА-Яа-яІіЇїЄєҐґ0-9._{}/-]+$")

    const val MAX_NUMBER_LENGTH = 128

    /**
     * Existing stored templates with {uuid} are supported without a migration.
     * New user-facing templates need {sequence}, but no visible UUID.
     */
    fun validate(policy: EstimateNumberPolicy): List<String> = buildList {
        if (policy.template.isEmpty() || policy.template.length > 96) {
            add("Template must contain 1–96 characters")
        }
        if (!safeTemplate.matches(policy.template)) {
            add("Template contains unsupported characters")
        }
        val tokens = templateToken.findAll(policy.template).toList()
        val names = tokens.map { it.groupValues[1] }
        if (names.any { it !in placeholders }) {
            add("Template contains an unknown placeholder")
        }
        if (names.groupingBy { it }.eachCount().any { it.value > 1 }) {
            add("Template may not repeat placeholders")
        }
        if (templateToken.replace(policy.template, "").any { it == '{' || it == '}' }) {
            add("Template contains an invalid placeholder")
        }
        // Legacy UUID-only format is still readable but new numbers must
        // always contain a local sequence to avoid accidental local duplicates.
        if ("sequence" !in names && "uuid" !in names) {
            add("Template must contain {sequence}")
        }
        if ("prefix" in names && !safePrefix.matches(policy.prefix)) {
            add("Prefix must contain 1–20 letters, digits, - or _")
        }
        if (policy.suffixLabel.isNotEmpty() && !safePrefix.matches(policy.suffixLabel)) {
            add("Suffix label must contain at most 20 letters, digits, - or _")
        }
        if (policy.sequenceWidth !in 1..12) {
            add("Sequence width must be between 1 and 12")
        }
        val maxRenderedLength = policy.template.length - tokens.sumOf { it.value.length } +
            tokens.sumOf {
                when (it.groupValues[1]) {
                    "suffix" -> policy.suffixLabel.length + 11
                    "uuid" -> 36
                    "prefix" -> policy.prefix.length
                    "year" -> 4
                    "sequence" -> 19
                    else -> 0
                }
            } + (if ("uuid" in names) 0 else 37)
        if (maxRenderedLength > MAX_NUMBER_LENGTH) {
            add("Document number could exceed $MAX_NUMBER_LENGTH characters")
        }
    }

    /** Internal immutable identity: the full RFC-4122 v4 UUID is always present. */
    fun render(policy: EstimateNumberPolicy, context: EstimateNumberContext): String {
        require(validate(policy).isEmpty()) { validate(policy).joinToString("; ") }
        val uuid = context.seriesUuid.lowercase()
        require(uuidV4.matches(uuid)) { "Series UUID must be a complete RFC 4122 version-4 UUID" }
        require(context.year in 1000..9999) { "Year must contain four digits" }
        require(context.localSequence > 0) { "Local sequence must be positive" }
        val formatted = substitute(policy, context)
        val canonical = if ("{uuid}" in policy.template) formatted else "$formatted-$uuid"
        require(canonical.length <= MAX_NUMBER_LENGTH) { "Document number is too long" }
        return canonical
    }

    /** Public preview only; it never shows or reserves an actual UUID. */
    fun preview(policy: EstimateNumberPolicy): String = printable(
        render(policy, EstimateNumberContext("12345678-1234-4abc-8def-0123456789ab", 2026, 1, "K7Q2M8TR92"))
    )

    /**
     * An already-issued canonical identifier yields the same public label
     * even if the user's template changes. No DB writes or clock required.
     * Legacy UUID-only documents get a stable local-sequence fallback.
     */
    fun printable(canonicalNumber: String, localSequence: Long = 0L, prefix: String = "AC",
                  sequenceWidth: Int = 5): String {
        val withoutUuid = uuidPart.replace(canonicalNumber, "").trim('-', '_', '.', '/')
        // Collapse separators surrounding a UUID in legacy templates such as
        // {prefix}-{uuid}-{sequence}, while preserving intentional single separators.
        val cleaned = withoutUuid.replace(Regex("--+"), "-")
            .replace(Regex("__+"), "_")
            .replace(Regex("//+"), "/")
            .trim('-', '_', '.', '/')
        return cleaned.ifBlank {
            if (localSequence > 0) "$prefix-${localSequence.toString().padStart(sequenceWidth, '0')}"
            else "Кошторис"
        }
    }

    /** UI editor only: strip the private UUID placeholder from legacy formats. */
    fun printableTemplate(internalTemplate: String): String {
        val cleaned = internalTemplate.replace("{uuid}", "")
            .replace(Regex("--+"), "-").replace(Regex("__+"), "_")
            .replace(Regex("//+"), "/").trim('-', '_', '.', '/')
        return cleaned
    }

    /** A separate random UUID, never the private series identity. 40 random bits. */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun newPublicToken(): String = publicToken(kotlin.uuid.Uuid.random().toString())

    fun publicToken(randomUuid: String): String {
        require(uuidV4.matches(randomUuid.lowercase()))
        return randomUuid.replace("-", "").take(10).uppercase()
    }

    fun validatePublic(policy: EstimateNumberPolicy): List<String> = validate(policy) + buildList {
        if ("{suffix}" !in policy.template) add("Public template must contain {suffix}")
        if ("{sequence}" !in policy.template) add("Public template must contain {sequence}")
        if ("{uuid}" in policy.template) add("Public template must not contain UUID")
    }

    private fun substitute(policy: EstimateNumberPolicy, context: EstimateNumberContext): String {
        if ("{suffix}" in policy.template) require(Regex("^[A-Z0-9]{8,10}$").matches(context.publicToken))
        val values = mapOf(
            "suffix" to listOf(policy.suffixLabel, context.publicToken).filter { it.isNotEmpty() }.joinToString("-"),
            "prefix" to policy.prefix,
            "year" to context.year.toString(),
            "sequence" to context.localSequence.toString().padStart(policy.sequenceWidth, '0'),
            "uuid" to context.seriesUuid.lowercase()
        )
        return templateToken.replace(policy.template) { match -> values.getValue(match.groupValues[1]) }
    }
}
