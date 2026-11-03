package com.andreyceilings.shared.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlCipherRowsetSqlTest {
    @Test
    fun onlyLeadingDmlIsAdaptedAndOriginalSqlIsPreserved() {
        listOf(
            "SELECT 'INSERT RETURNING'",
            "PRAGMA cipher_version",
            "EXPLAIN SELECT 1",
            "WITH source AS (SELECT 1) SELECT * FROM source"
        ).forEach { assertEquals(it, sqlCipherRowsetSql(it)) }
        listOf(
            "INSERT INTO t VALUES (?) RETURNING id",
            "  update t SET value = ? RETURNING id",
            "-- INSERT is only a comment\n/* comment */ DELETE FROM t RETURNING id",
            "REPLACE INTO t VALUES (?) RETURNING id"
        ).forEach { sql ->
            val adapted = sqlCipherRowsetSql(sql)
            assertTrue(adapted.startsWith("WITH "))
            assertTrue(adapted.endsWith("\n$sql"), "Bindings, comments and mutation must remain intact")
        }
    }
}
