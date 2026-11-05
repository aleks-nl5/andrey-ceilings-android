package com.andreyceilings.app.data.security

import com.andreyceilings.app.data.db.DatabaseStartupDiagnostics
import com.andreyceilings.app.data.db.DatabaseStartupFailure
import com.andreyceilings.shared.db.EncryptedDatabaseOpenFailure
import com.andreyceilings.shared.db.EncryptedDatabaseOpenStage
import org.junit.Assert.*
import org.junit.Test

class DatabaseStartupDiagnosticsTest {
    @Test fun fingerprintAndStatementCodesContainOnlySafeIdentifiers() {
        val failure=EncryptedDatabaseOpenFailure(EncryptedDatabaseOpenStage.MIGRATE,
            com.andreyceilings.shared.db.SchemaFingerprintMismatch("ceiling_operation_validate"),10,11)
        assertEquals("DB-MIGRATE-10-11-FP-ceiling_operation_validate",DatabaseStartupDiagnostics.report(failure).code)
        val statement=EncryptedDatabaseOpenFailure(EncryptedDatabaseOpenStage.MIGRATE,
            com.andreyceilings.shared.db.MigrationStatementFailure(3,IllegalStateException("private SQL/key/value")),10,11)
        val report=DatabaseStartupDiagnostics.report(statement)
        assertEquals("DB-MIGRATE-10-11-S3",report.code)
        assertFalse((report.status+report.debugTrace).contains("private SQL/key/value"))
        assertThrows(IllegalArgumentException::class.java) {com.andreyceilings.shared.db.SchemaFingerprintMismatch("private-customer-name")}
    }
    @Test fun startupCodeAndTraceExcludeMessagesSqlPathsKeysAndPii() {
        val secret="SELECT customer='private-client' FROM /private/path KEY secret-key"
        val original=IllegalStateException(secret,java.io.IOException(secret))
        val failure=EncryptedDatabaseOpenFailure(EncryptedDatabaseOpenStage.MIGRATE,original,9,10)
        val report=DatabaseStartupDiagnostics.report(failure)
        assertEquals("DB-MIGRATE-9-10",report.code)
        val exported=report.status+report.debugTrace
        listOf("private-client","/private/path","secret-key","SELECT").forEach { assertFalse(exported.contains(it)) }
        assertTrue(report.debugTrace.contains("java.io.IOException"))
        assertTrue(report.debugTrace.contains("startupCodeAndTraceExcludeMessagesSqlPathsKeysAndPii"))
        assertEquals("DB-KEY",DatabaseStartupDiagnostics.report(DatabaseStartupFailure(DatabaseStartupFailure.Stage.KEY,original)).code)
    }
}
