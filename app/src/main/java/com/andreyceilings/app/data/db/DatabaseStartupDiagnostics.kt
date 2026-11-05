package com.andreyceilings.app.data.db

import com.andreyceilings.shared.db.EncryptedDatabaseOpenFailure
import com.andreyceilings.shared.db.SchemaFingerprintMismatch
import com.andreyceilings.shared.db.MigrationStatementFailure

internal class DatabaseStartupFailure(val stage: Stage, cause: Throwable) : IllegalStateException("Database startup failed", cause) {
    enum class Stage { RECOVERY, KEY, SESSION }
}

/** Never includes exception messages, SQL, paths, database records or key bytes. */
internal object DatabaseStartupDiagnostics {
    data class Report(val code: String, val explanation: String, val debugTrace: String) {
        val status get() = "Не вдалося відкрити захищену базу. Код: $code. $explanation Дані не скинуто. Перевірка резервної копії доступна в налаштуваннях."
    }
    fun report(failure: Throwable): Report {
        val chain = generateSequence(failure) { it.cause }.take(12).toList()
        val encrypted = chain.filterIsInstance<EncryptedDatabaseOpenFailure>().firstOrNull()
        val startup = chain.filterIsInstance<DatabaseStartupFailure>().firstOrNull()
        val baseCode = when {
            encrypted != null -> "DB-${encrypted.stage.name}" +
                if (encrypted.fromVersion != null && encrypted.toVersion != null) "-${encrypted.fromVersion}-${encrypted.toVersion}" else ""
            startup != null -> "DB-${startup.stage.name}"
            else -> "DB-UNKNOWN"
        }
        val fingerprint=chain.filterIsInstance<SchemaFingerprintMismatch>().firstOrNull()
        val statement=chain.filterIsInstance<MigrationStatementFailure>().firstOrNull()
        val code=baseCode+(fingerprint?.let {"-FP-${it.objectId}"} ?: "")+(statement?.let {"-S${it.statement}"} ?: "")
        val explanation = when {
            fingerprint != null -> "Перевірка сумісності структури відхилила відкриття. Передайте цей код розробнику."
            encrypted != null -> "Збій на етапі відкриття або перевірки структури БД. Передайте цей код розробнику."
            startup?.stage == DatabaseStartupFailure.Stage.KEY -> "Захищений ключ недоступний. Передайте цей код розробнику; не очищуйте дані застосунку."
            startup?.stage == DatabaseStartupFailure.Stage.RECOVERY -> "Попереднє відновлення потребує безпечного відкату. Передайте цей код розробнику."
            else -> "Причину не встановлено. Передайте цей код розробнику."
        }
        val trace = buildString {
            append(code)
            chain.forEach { cause ->
                append("\n").append(cause.javaClass.name)
                cause.stackTrace.take(24).forEach { frame ->
                    append("\n at ").append(frame.className).append('.').append(frame.methodName).append(':').append(frame.lineNumber)
                }
            }
        }
        return Report(code,explanation,trace)
    }
}
