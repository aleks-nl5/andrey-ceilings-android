package com.andreyceilings.app.data.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Coordinates the production SQLDelight driver with destructive SQLCipher
 * restore. A restore must wait for all in-flight operations AND full
 * transactions to finish before it closes/replaces the encrypted DB.
 *
 * Disposable instrumentation drivers are deliberately not wrapped.
 */
internal object ProductionDatabaseGate {
    private val access = ReentrantReadWriteLock(true)

    fun <R> read(block: () -> R): R {
        val lock = access.readLock()
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun <R> restore(block: () -> R): R {
        check(access.readHoldCount == 0 || access.isWriteLockedByCurrentThread) {
            "Не можна почати імпорт усередині активної транзакції БД."
        }
        val exclusive = access.writeLock()
        check(exclusive.tryLock(30, TimeUnit.SECONDS)) {
            "База даних зараз використовується. Дочекайтеся завершення операцій і повторіть імпорт."
        }
        try {
            return block()
        } finally {
            exclusive.unlock()
        }
    }

    /**
     * A statement holds a read lease until its mapper finishes. A SQLDelight
     * transaction holds the same lease until AFTER its commit/rollback;
     * otherwise a restore could close the driver in the middle of a write.
     */
    fun wrap(driver: SqlDriver): SqlDriver = RestorableSqlDriver(driver, access)
}

private class RestorableSqlDriver(
    private val delegate: SqlDriver,
    private val access: ReentrantReadWriteLock
) : SqlDriver by delegate {
    private var closed = false

    private inline fun <R> withRead(action: () -> R): R {
        val lock = access.readLock()
        lock.lock()
        try {
            check(!closed) { "БД була замінена. Повторіть дію на оновленій базі." }
            return action()
        } finally {
            lock.unlock()
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<Long> = withRead {
        delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<R> = withRead {
        delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val lock = access.readLock()
        lock.lock()
        try {
            check(!closed) { "БД була замінена. Повторіть дію на оновленій базі." }
            val result = delegate.newTransaction()
            val transaction = result.value
            // SQLDelight runs these callbacks only after ending the
            // transaction. Nested callbacks are deferred until the parent
            // transaction ends, so all read-lock holds are released.
            transaction.afterCommit { lock.unlock() }
            transaction.afterRollback { lock.unlock() }
            return result
        } catch (failure: Throwable) {
            lock.unlock()
            throw failure
        }
    }

    override fun currentTransaction(): Transacter.Transaction? = withRead {
        delegate.currentTransaction()
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = withRead {
        delegate.addListener(*queryKeys, listener = listener)
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = withRead {
        delegate.removeListener(*queryKeys, listener = listener)
    }

    override fun notifyListeners(vararg queryKeys: String) = withRead {
        delegate.notifyListeners(*queryKeys)
    }

    override fun close() {
        // All production close/reset callers hold the exclusive restore lock.
        closed = true
        delegate.close()
    }
}
