# Windows SQLCipher/JDBC RETURNING compatibility (#67)

> **Виконано 03.11.2026:** PR #68 [merged](https://github.com/aleks-nl5/andrey-ceilings-android/pull/68), issue #67 **closed**. Windows native encrypted SQLCipher CRUD/`RETURNING` і захист fail-before-write проходять CI; це більше не блокер для CMP Measurement Editor PR A. Технічні деталі сумісності pinned driver 0.2.13 нижче залишаються актуальними.

## Audit and reproduction

Base: main after PR #66 (`5e77bae`). SQLDelight 2.4.1 generates
`driver.executeQuery(...)` for DML with RETURNING and calls `notifyQueries` after
successful execution. BusinessRepositories already use those generated queries
for client/project/measurement/estimate creation, price insertion and optimistic
price updates/tombstones. EstimateHistory also uses UPDATE RETURNING to allocate
numbers and INSERT ON CONFLICT DO NOTHING RETURNING to reserve series identities.
These SQL statements and repository APIs remain unchanged.

SQLDelight's `JdbcDriver` delegates to `PreparedStatement.executeQuery()`.
The encrypted `sqlcipher-multiplatform-jdbc-core:0.2.13` implementation in
`ProxyJdbc.kt` classifies SQL using `looksLikeQuery`: only SELECT, PRAGMA, WITH
and EXPLAIN select its rowset path. Plain INSERT/UPDATE/DELETE RETURNING therefore
throws `executeQuery cannot be used for non-query SQL` before stepping SQLite.

Simply switching to `execute()`/`getResultSet()` does **not** solve this version's
problem. Its non-query path steps SQLite, sees SQLITE_ROW, resets the statement
and throws `Statement produced a result set. Use execute()/executeQuery()`.
There is no readable ResultSet, and the mutation has already run. Retrying the
original write would be unsafe.

`SqlCipherWindowsReturningTest.nativeJdbcDispatchProbe` checks those behaviors
on a real encrypted native Windows database in a BEGIN/ROLLBACK transaction.
The first regression-only commit (`9376227`) reproduced the production failure
in [Windows CI](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41603946983/job/125809052714):
181 tests, exactly one failing generated returning-ID insert; the raw dispatch
probe passed. Plain SQLite JDBC is not used as acceptance evidence.

## Adapter solution

`ConfiguredSqlCipherJdbcDriver.executeQuery` adds an unused CTE **only** to
leading INSERT/UPDATE/DELETE/REPLACE queries requested through SQLDelight's query
API. For example:

```sql
WITH __sqldelight_returning AS (SELECT 1)
INSERT INTO clients(...) VALUES (...) RETURNING id
```

The original SQL and bind parameters are preserved. The CTE name is extended
until it does not occur in the original SQL, preventing identifier shadowing.
Leading whitespace/comments are accepted. Already WITH-prefixed queries, SELECT,
PRAGMA and EXPLAIN are left intact; ordinary non-returning DML keeps `execute`.

SQLite executes one prepared statement with its original RETURNING clause.
The facade's supported WITH path exposes the native result through
`PreparedStatement.executeQuery()`, mapped by SQLDelight's normal
`JdbcCursor`/`JdbcPreparedStatement` classes. Multirow and empty results retain
their native SQLite semantics, including ON CONFLICT and optimistic no-match.
There is no last_insert_rowid(), identity re-query, reflection/native-handle access,
second connection, or read-then-write emulation.

### Reject query-API DML without RETURNING before writing

The review of `412b9cd` identified that a mistaken `executeQuery(INSERT ...)`
without RETURNING could expose a zero-column cursor; consuming it could perform
the write despite the caller expecting a query. The adapter now checks
`ResultSet.metaData.columnCount` before invoking the mapper and throws
`SQLException` when there are no result columns. In pinned core 0.2.13 this
metadata is captured by `sqlite3_column_count` immediately after prepare; neither
`executeQuery()` nor reading/closing the result calls `sqlite3_step`. Only
`ResultSet.next()` steps the statement. The native metadata probe verifies that
rejecting/closing this cursor leaves the table unchanged.

This uses SQLite's actual result shape instead of a RETURNING text search or a
new SQL parser. Literals, escaped strings, quoted identifiers, comments and CTE
bodies containing the word RETURNING cannot bypass the check. A real RETURNING
clause has columns even when zero rows match, so legitimate no-match queries
remain supported. Already WITH-prefixed non-returning DML is guarded as well.
The query API uses JDBC `executeQuery()` rather than `execute()` so SQL that the
facade does not recognize as a query is rejected before its non-query step path.
Ordinary DML callers continue to use `SqlDriver.execute()`.

`connectionAndClose()` keeps SQLDelight's existing thread-local transaction
connection, including nesting. ResultSet and Statement have nested `use` scopes
covering binding, stepping and mapping failures; the connection is released in
`finally` outside a transaction and by the original commit/rollback hooks inside
one. Mapper failures after a write require a surrounding transaction for rollback,
as with normal JDBC operations. Generated `notifyQueries` and transaction-deferred
notifications are unchanged.

The concurrent native test additionally exposed the facade's zero lock-wait
default (`database is locked`). Each physical connection now receives a bounded
5000ms SQLite busy timeout before schema validation. SQLite waits for its lock;
the adapter never retries a possibly executed write. Encryption parameters,
foreign-key enforcement, WAL and key ownership/zeroing remain unchanged.

## Verification and boundaries

Mandatory Windows tests exercise:

- Generated returning-ID writes for all five business repositories, relation IDs,
  encrypted reopen and wrong-key rejection without changed database bytes.
- Price update/delete optimistic matches, stale/wrong-code/deleted no-match,
  and competing optimistic updates with exactly one winner.
- Physical DELETE RETURNING, multiple/zero rows, ON CONFLICT, bound text/real/
  integer flags/blob/null values, an existing CTE and CTE-name collision.
- Query-API INSERT/UPDATE/DELETE/REPLACE without RETURNING fails before mapper
  invocation and leaves rows unchanged, outside and inside a transaction;
  false RETURNING tokens in strings/comments/identifiers/CTEs do not bypass it.
- Commit, rollback after nested success, nested failure, numbering/immutable
  revision rollback after constraint failure, concurrent related writes.
- Actual Flow updates on insert/update/tombstone and deferred commit notifications
  without rollback notifications; ordinary SELECT/INSERT/UPDATE/DELETE regressions.
- Binding/mapping/preparation/constraint failures, partial result consumption,
  subsequent writes and removal of the database directory after closing handles.

Run on Windows with the existing native encrypted runtime:

```text
gradle --no-daemon :desktopApp:compileKotlin :shared:jvmTest :desktopApp:test
```

The test bodies intentionally run only on Windows, where that native artifact is
packaged; a Linux JVM run alone cannot establish acceptance. A temporary native
Linux SQLCipher diagnostic can help investigate the same core facade but does not
replace the Windows gate. The pure SQL adaptation test also runs on other JVMs.
This is a compatibility adapter for the pinned facade, not a general JDBC
implementation; its unrelated API limitations (for example ResultSet.getBoolean,
with SQLite flags read as integers in current queries) are not expanded here.

No schema/migration, repository business rule, encryption/key management, WAL,
backup, UUID/tombstone, immutable history/PDF/numbering algorithm, mathematics,
CMP UI, Gradle version/dependency or CI workflow change is required.

Final-SHA CI links and measured timings/cache observations are recorded in
[merged PR #68](https://github.com/aleks-nl5/andrey-ceilings-android/pull/68) and
[#67](https://github.com/aleks-nl5/andrey-ceilings-android/issues/67).
Baseline PR #66 final head `30f9cd3`: Android job 7m10s (emulator 3m18s), Windows
3m36s (combined JVM/Desktop 1m40s), macOS 17m46s (framework 12m57s, Simulator
2m09s, JVM 1m25s). Android/Windows had Gradle-home hits; macOS missed and downloaded
Native/LLVM. Compare job execution time separately from queue time and cache state;
do not attribute cold downloads or expanded native tests to compiler regressions.
