package com.andreyceilings.app.data.security

import com.andreyceilings.app.data.db.DatabaseStartupDiagnostics
import com.andreyceilings.app.data.db.DatabaseStartupFailure
import com.andreyceilings.shared.db.EncryptedDatabaseOpenFailure
import com.andreyceilings.shared.db.EncryptedDatabaseOpenStage
import org.junit.Assert.*
import org.junit.Test

class DatabaseStartupDiagnosticsTest {
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
