package com.andreyceilings.shared.domain

/**
 * Independent stages of performing work. Do not interpret payment as a completed
 * installation: historic records used a single status field for both concerns.
 */
enum class ProjectWorkStage(val wireId: String, val label: String) {
    MEASUREMENT("measurement", "Замір"),
    CALCULATION("calculation", "Розрахунок"),
    APPROVED("approved", "Погоджено"),
    IN_PROGRESS("in_progress", "Монтаж"),
    REWORK("rework", "Доопрацювання"),
    COMPLETED("completed", "Здано"),
    CANCELLED("cancelled", "Скасовано");

    companion object {
        fun fromWireId(value: String): ProjectWorkStage? =
            entries.firstOrNull { it.wireId == value }
    }
}

/** "unknown" is necessary when migrating pre-existing records with no payment data. */
enum class ProjectPaymentStatus(val wireId: String, val label: String) {
    UNKNOWN("unknown", "Оплата не визначена"),
    UNPAID("unpaid", "Не оплачено"),
    PARTIALLY_PAID("partial", "Частково оплачено"),
    PAID("paid", "Оплачено");

    companion object {
        fun fromWireId(value: String): ProjectPaymentStatus =
            entries.firstOrNull { it.wireId == value } ?: UNKNOWN
    }
}

object ProjectStatusRules {
    /** A historical 'paid' workflow code is NOT evidence the work was completed. */
    fun workLabel(status: String): String = when (status) {
        "paid" -> "Етап не визначено"
        else -> ProjectWorkStage.fromWireId(status)?.label ?: "Невідомий етап"
    }

    fun paymentLabel(paymentStatus: String): String =
        ProjectPaymentStatus.fromWireId(paymentStatus).label

    fun isKnownWorkStage(status: String): Boolean =
        ProjectWorkStage.fromWireId(status) != null

    fun isKnownPaymentStatus(status: String): Boolean =
        ProjectPaymentStatus.entries.any { it.wireId == status }
}
