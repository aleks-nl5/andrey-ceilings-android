package com.andreyceilings.shared.estimate

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

enum class EstimateType { INDIVIDUAL, CONSOLIDATED }

@Serializable
enum class EstimateCounterMode { INDEPENDENT, COMMON }

/** Resolved format frozen with each issued root. */
@Serializable
data class EstimateNumberPolicy(
    val template: String = STANDARD_ESTIMATE_TEMPLATE,
    val prefix: String = "КШ",
    val sequenceWidth: Int = 4
)

const val STANDARD_ESTIMATE_TEMPLATE = "{prefix}-{year}-{sequence}"

@Serializable
data class EstimateIssuancePolicy(
    val mode: EstimateCounterMode = EstimateCounterMode.INDEPENDENT,
    val individualPrefix: String = "КШ",
    val consolidatedPrefix: String = "ЗК",
    val template: String = STANDARD_ESTIMATE_TEMPLATE,
    val consolidatedTemplate: String? = null,
    val sequenceWidth: Int = 4
) {
    fun resolve(type: EstimateType): EstimateNumberPolicy = when (type) {
        EstimateType.INDIVIDUAL -> EstimateNumberPolicy(template, individualPrefix, sequenceWidth)
        EstimateType.CONSOLIDATED -> EstimateNumberPolicy(consolidatedTemplate ?: template, consolidatedPrefix, sequenceWidth)
    }
}

data class EstimateNumberPreview(
    val individual: String,
    val consolidated: String,
    val alternatives: Boolean
)

object EstimateNumbering {
    private val token = Regex("\\{([a-zA-Z]+)\\}")
    private val known = setOf("prefix", "year", "yearShort", "month", "day", "sequence")
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", RegexOption.IGNORE_CASE)
    private const val MAX_PUBLIC_LENGTH = 91

    // Accept BMP letters and decimal digits and this explicitly permitted punctuation.
    // Compare prefixes using Kotlin's locale-independent lowercase and exact code points.
    // Formatting preserves all accepted characters, including repeated separators.
    private fun safePrefixChar(char: Char) = char.isLetterOrDigit() || char in "-_"

    fun validatePublic(policy: EstimateNumberPolicy): List<String> = buildList {
        val matches = token.findAll(policy.template).toList()
        val names = matches.map { it.groupValues[1] }
        val literal = token.replace(policy.template, "")
        if (policy.template.length !in 1..96 || literal.any { !safePrefixChar(it) && it !in " ./·+№()" }) {
            add("Template contains unsupported characters or exceeds 96 characters")
        }
        if (names.any { it !in known } || names.distinct().size != names.size) {
            add("Template contains an unknown or repeated token")
        }
        if ("prefix" !in names || "sequence" !in names || names.count { it == "year" || it == "yearShort" } != 1) {
            add("Template requires prefix, one year and sequence")
        }
        if (policy.prefix.length !in 1..20 || !policy.prefix.all(::safePrefixChar) ||
            policy.prefix.none(Char::isLetterOrDigit)) {
            add("Prefix must contain 1–20 letters, digits, hyphens or underscores")
        }
        if (policy.sequenceWidth !in 1..12) add("Sequence width must be between 1 and 12")
        val longest = literal.length + names.sumOf { name ->
            when (name) {
                "prefix" -> policy.prefix.length
                "year" -> 4
                "sequence" -> 19
                else -> 2
            }
        }
        if (uuid.containsMatchIn(policy.template)) add("Template must not contain a UUID")
        if (longest > MAX_PUBLIC_LENGTH) add("Public number could exceed $MAX_PUBLIC_LENGTH characters")
    }

    fun validate(policy: EstimateIssuancePolicy): List<String> = buildList {
        for (type in EstimateType.entries) addAll(validatePublic(policy.resolve(type)))
        if (policy.individualPrefix.lowercase() == policy.consolidatedPrefix.lowercase()) {
            add("Prefixes must differ")
        }
    }

    fun issueDate(epochMillis: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date

    fun render(policy: EstimateNumberPolicy, date: LocalDate, sequence: Long): String {
        require(validatePublic(policy).isEmpty())
        require(date.year in 1000..9999 && sequence > 0)
        val isoDate = date.toString()
        val values = mapOf(
            "prefix" to policy.prefix,
            "year" to isoDate.substring(0, 4),
            "yearShort" to isoDate.substring(2, 4),
            "month" to isoDate.substring(5, 7),
            "day" to isoDate.substring(8, 10),
            "sequence" to sequence.toString().padStart(policy.sequenceWidth, '0')
        )
        return token.replace(policy.template) { values.getValue(it.groupValues[1]) }.also {
            require(!uuid.containsMatchIn(it)) { "Public number must not contain a UUID" }
        }
    }

    internal fun canonical(publicNumber: String, seriesUuid: String): String {
        require(isEstimateUuid(seriesUuid))
        return "$publicNumber-$seriesUuid"
    }

    /** Canonical v22 IDs end with exactly one private UUID; preserve the full public part. */
    fun printable(canonicalNumber: String): String {
        val privateId = canonicalNumber.takeLast(36)
        return if (canonicalNumber.length > 37 && canonicalNumber[canonicalNumber.length - 37] == '-' &&
            isEstimateUuid(privateId)) canonicalNumber.dropLast(37) else canonicalNumber
    }
}
