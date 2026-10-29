package com.andreyceilings.shared.estimate

/**
 * A format for NEW estimate-series document numbers. The stored document number
 * is a snapshot: changing this policy must never rewrite existing estimates.
 *
 * The full 128-bit series UUID is mandatory. An offline client cannot promise
 * mathematically collision-free UUIDs without coordinating with other clients;
 * persistence must enforce UNIQUE(seriesUuid) and UNIQUE(documentNumber).
 */
data class EstimateNumberPolicy(
    val template: String = "{prefix}-{year}-{sequence}-{uuid}",
    val prefix: String = "AC",
    val sequenceWidth: Int = 5
)

data class EstimateNumberContext(
    val seriesUuid: String,
    val year: Int,
    val localSequence: Long
)

/** Pure and deterministic on Android, Windows/JVM and iOS; does not touch the DB or clock. */
object EstimateNumbering {
    private val placeholders = setOf("prefix", "year", "sequence", "uuid")
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val templateToken = Regex("\\{([a-z]+)\\}")
    private val safePrefix = Regex("^[A-Za-z0-9_-]{1,20}$")
    private val safeTemplate = Regex("^[A-Za-z0-9._{}-]+$")

    const val MAX_NUMBER_LENGTH = 128

    /** Safe to call while editing settings, without generating or persisting a document. */
    fun validate(policy: EstimateNumberPolicy): List<String> = buildList {
        if (policy.template.isEmpty() || policy.template.length > 96) {
            add("Template must contain 1–96 characters")
        }
        if (!safeTemplate.matches(policy.template)) {
            add("Template contains unsupported characters")
        }
        val tokens = templateToken.findAll(policy.template).toList()
        val names = tokens.map { it.groupValues[1] }
        if (names.count { it == "uuid" } != 1) {
            add("Template must contain {uuid} exactly once")
        }
        if (names.any { it !in placeholders }) {
            add("Template contains an unknown placeholder")
        }
        if (names.groupingBy { it }.eachCount().any { it.value > 1 }) {
            add("Template may not repeat placeholders")
        }
        if (templateToken.replace(policy.template, "").any { it == '{' || it == '}' }) {
            add("Template contains an invalid placeholder")
        }
        if ("prefix" in names && !safePrefix.matches(policy.prefix)) {
            add("Prefix must contain 1–20 ASCII letters, digits, - or _")
        }
        if (policy.sequenceWidth !in 1..12) {
            add("Sequence width must be between 1 and 12")
        }
        // Worst case: all placeholders rendered with their maximum widths.
        // Helps avoid saving settings that only fail when numbers grow.
        val maxRenderedLength = policy.template.length - tokens.sumOf { it.value.length } +
            tokens.sumOf {
                when (it.groupValues[1]) {
                    "uuid" -> 36
                    "prefix" -> policy.prefix.length
                    "year" -> 4
                    "sequence" -> 19 // Long.MAX_VALUE; padding is at most 12
                    else -> 0
                }
            }
        if (maxRenderedLength > MAX_NUMBER_LENGTH) {
            add("Document number could exceed $MAX_NUMBER_LENGTH characters")
        }
    }

    /** Format only: allocating the UUID/sequence and committing the number belong in one DB transaction. */
    fun render(policy: EstimateNumberPolicy, context: EstimateNumberContext): String {
        val errors = validate(policy)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        val uuid = context.seriesUuid.lowercase()
        require(uuidV4.matches(uuid)) { "Series UUID must be a complete RFC 4122 version-4 UUID" }
        require(context.year in 1000..9999) { "Year must contain four digits" }
        require(context.localSequence > 0) { "Local sequence must be positive" }
        val values = mapOf(
            "prefix" to policy.prefix,
            "year" to context.year.toString(),
            "sequence" to context.localSequence.toString().padStart(policy.sequenceWidth, '0'),
            "uuid" to uuid
        )
        val number = templateToken.replace(policy.template) { match -> values.getValue(match.groupValues[1]) }
        require(number.length <= MAX_NUMBER_LENGTH) { "Document number is too long" }
        return number
    }

    /** Preview is read-only; caller may display an illustrative UUID and sequence. */
    fun preview(policy: EstimateNumberPolicy): String = render(
        policy,
        EstimateNumberContext("12345678-1234-4abc-8def-0123456789ab", 2026, 1)
    )
}
