# Issue #147 — source audit checkpoint (SQLDelight 2.4.1 / SQLCipher JDBC 0.2.13)

> **Partial source audit, 22 November 2026; not a proof that connection pooling is safe.** This document records specific verified code paths and testable hazards. See [canonical handoff](ISSUE_147_WINDOWS_SQLCIPHER_REUSE_HANDOFF.md) and [issue #147](https://github.com/aleks-nl5/andrey-ceilings-android/issues/147). Do not convert this research into an unconditional pool or merge without real Windows CI evidence.

## Pinned dependencies and original sources

- Project: `shared/build.gradle.kts` pins SQLDelight `2.4.1`, SQLCipher JDBC core and Windows-native artifact `0.2.13`. The Windows driver implementation is `shared/src/jvmMain/kotlin/com/andreyceilings/shared/db/SqlCipherWindowsDriverFactory.kt`.
- Exact SQLDelight tag: [`JdbcDriver.kt` at 2.4.1](https://github.com/sqldelight/sqldelight/blob/2.4.1/drivers/jdbc-driver/src/main/kotlin/app/cash/sqldelight/driver/jdbc/JdbcDriver.kt). The `ConnectionManager` interface and nested `ConnectionManager.Transaction` are **in that file**, not a separate `ConnectionManager.kt`.
- Exact native facade tag: [`ProxyJdbc.kt` at v0.2.13](https://github.com/s0d3s/SQLCipherMultiplatform/blob/v0.2.13/sqlcipher-multiplatform-jdbc-core/src/main/kotlin/io/github/s0d3s/sqlcipher_multiplatform_jdbc/ProxyJdbc.kt). Do not substitute main or a newer artifact when reviewing behavior.
- Local behavior: `JdbcDriver.connectionAndClose()` calls `getConnection()` for every nontransactional operation and returns `closeConnection(connection)` as the scope cleanup. With a current `transaction`, it reuses `transaction.connection`. This is why changing only the physical connection lifecycle could remove redundant encrypted schema guards **without** enlarging logical transactions.

## Source-confirmed hazards

1. **ThreadLocal, not coroutine-local.** `JdbcDriver.transaction` is stored in a `ThreadLocal`. `newTransaction()` obtains a new connection on the current thread, creates a `ConnectionManager.Transaction`, publishes it into the `ThreadLocal`, then calls `Connection.beginTransaction()` only for the outer transaction. Nested transactions share the same connection. Calling suspending code that resumes on a different thread inside a JDBC transaction is not safe by construction. Audit actual call paths/dispatchers; simply using `Dispatchers.Default.limitedParallelism(1)` does not establish thread identity.
2. **Statement finalization matters before reuse.** SQLDelight `JdbcDriver.execute()` creates a `JdbcPreparedStatement`, calls its `execute()`, then releases the connection, but does not explicitly close that PreparedStatement. SQLCipher `v0.2.13` `PreparedStatement.close()` calls `NativeBridge.finalizeStmt(statementHandle)`. With per-statement physical close this was partly hidden; a reusable physical connection could retain native statement handles indefinitely. This branch now overrides the ordinary execute path with an explicit `prepareStatement(sql).use { ... }` while maintaining SQLDelight's returned count and the special atomic `PRAGMA user_version` consumption. This is a **prerequisite**, not pooling.
3. **Shared proxy state has no synchronization.** The exact SQLCipher JDBC facade's `ConnectionState` stores mutable `nativeHandle`, `closed`, `autoCommit`, and savepoint state. `ConnectionHandler`/Statement/ResultSet use direct mutable fields and native calls. A physical Connection must not be in use by two threads or callers at once; even *sequential* cross-thread re-leasing requires native Windows proof. No global singleton and no unbounded per-thread cache.
4. **Returning an uncertain connection is unsafe.** SQLDelight hands `closeConnection` no success/failure flag. In our driver the `onClose()` callback runs in `finally` for both success and exceptions. Simply reimplementing `closeConnection` to add every connection to an idle queue could reuse one left in an unknown SQLite/native state after statement, mapper, BEGIN, COMMIT or ROLLBACK failure. A future pool must distinguish healthy resettable idle connections from ones needing physical close, and must handle failures inside transactions explicitly.
5. **BEGIN failure can leak ThreadLocal state.** SQLDelight `newTransaction()` sets `transaction` before `beginTransaction()`; if native BEGIN throws, its method does not restore the `ThreadLocal` in that path. `Transaction.endTransaction()` does reset it in a `finally` for normal transaction completion. Any pooling design needs targeted tests and an explicit strategy for begin failures and cleanup; cannot assume all SQLDelight callbacks are balanced after exceptions.
6. **Close and key lifecycle differ today.** Current `ConfiguredSqlCipherJdbcDriver.close()` wipes its copied owned key and prevents new physical opens; no physical idle handles exist today. Adding them means `close()` must atomically reject new leases/opens, drain active leases (or fail clearly), close ALL native handles, then wipe key, including wrong-key and in-flight open failures. `DesktopPriceDatabase.close()` currently drains known measurement work; audit all other async callers before claiming global shutdown safety.
7. **Do not change transaction semantics for benchmark.** SQLCipher native facade classifies `WITH` and `PRAGMA` as rowset SQL; this project intentionally wraps RETURNING statements and consumes `user_version` updates. This path is tied to rollback/migration/CAS guarantees; do not alter or retry potentially completed mutations.

## Designs to compare (no selection yet)

| Approach | Potential benefit | Blocking proof/risk |
| --- | --- | --- |
| Bounded, exclusive-lease physical connection pool | Removes repeated physical key/schema guard between logical statements, permits controlled concurrent readers | Need strict lease ownership, transaction-pinned lifetime, invalidation, bounded waits, native reset, app shutdown drain, proof of sequential cross-thread handoff |
| Dedicated serialized database execution context | Single physical handle with fewer locks/guards, simpler ownership | Existing synchronous SQLDelight APIs, UI callbacks, independent concurrent driver/open tests and transaction/coroutine thread identity; risk of bottleneck, deadlocks or changed application semantics |
| Per-thread connection cache | Simple fit to `ThreadLocal` transactions | Unbounded dispatch thread count/native handles, thread reuse, stale isolation after exceptions, difficult deterministic close; **do not use without bounded ownership**, likely reject |

Preferred **candidate for investigation**: bounded exclusive-lease pool, not yet authorized as safe/production-ready. Start with a very small measured bound; compare unchanged business results and tests first. The SQLCipher JDBC facade does not itself give a documented connection pooling contract. No assumption of cross-thread native safety.

## Baseline instrumentation introduced in this branch (not yet measured)

An `internal` opt-in `SqlCipherWindowsConnectionMetrics` is passed only via `SqlCipherWindowsDriverFactory.openProfiled()`; normal `open()` passes null and preserves behavior. It counts actual physical opens/close completions, encrypted guard attempts/passes/duration, logical `execute`/`executeQuery`, pinned logical statements and outer BEGIN calls. No SQL strings, file paths, user data, passwords or key bytes in metrics/CI summaries, and no global metrics.

`SqlCipherWindowsConnectionProfileTest` now reports before/after segment **actual** physical counts for eight outside-transaction SQLDelight statements and eight in one pinned transaction, while retaining the original isolated raw JDBC phase medians. It verifies one successful encrypted schema guard per physical open, physical opens == completed physical closes after `driver.close()`, and exact logical statement counts. No fixed timing or physical-opening count threshold is set; the same test should survive a future safe implementation.

The representative expensive `PriceCatalogWindowsCipherTest` now also uses the opt-in profiled factory for its real encrypted driver openings, with per-JUnit-case in-memory counters printed after each test (no DB contents or secrets). This gives measured connection counts and guard cost within the slowest physical Windows class, without changing its SQL operations or expectations.

**Verification still required:** collect fresh Windows-native results on this branch; run isolated profile and slow classes with `--rerun-tasks`, and ALL unchanged Windows tests, plus Android/iOS FULL on the exact final SHA. Instrumentation does not make the pool safe. Source-level comparison is not a substitute for actual native concurrency/migration/corruption regressions.

---

## Final investigation checkpoint — 22 November 2026 (PR #148)

**Owner decision:** complete the bounded research/observability PR #148, leave issue #147 OPEN for deferred Windows performance work, and prioritize CI routing #134 followed by Android P5B. This PR does **not** deliver connection pooling or claim a measured Windows performance improvement. The owner alone decides when to merge; do not mark #147 done.

### Verified successful CI and duration (Gradle Windows full block, not whole job)

| Run | GitHub Actions | Windows Gradle duration | JUnit | Outcome |
| --- | --- | --- | --- | --- |
| #1048 | [44963316600](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/44963316600) | 45m 03s | 500 | PASS |
| #1055 | [44998739477](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/44998739477) | 1h 26m 06s | 500 | PASS |
| #1059 | [45012364505](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45012364505) | 44m 22s | 504 | PASS |
| #1063 | [45017088673](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45017088673) | 57m 07s | 504 | PASS |

The latest **code/test** HEAD `639377baf174e3cb845f303997ab5182b10fdfea` passed Windows, Android [#1319](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45017088701), and iOS. The later documentation commit is a different SHA and must not be called tested until its CI finishes. Variation from 44 to 86 minutes demonstrates **no stable full-suite speedup**. In #1063 `:desktopApp:test` was `FROM-CACHE`; most elapsed time was `:shared:jvmTest`. Do not compare cached Desktop timing with uncached source changes as if they were equivalent.

### Root cause proven for the expensive PriceCatalogWindowsCipherTest

Source: JUnit XML `shared/build/test-results/jvmTest/TEST-com.andreyceilings.shared.db.PriceCatalogWindowsCipherTest.xml`, artifact **windows-desktop-test-reports** ID **14754117571**, Windows run #1063. Five real encrypted-DB testcases, all passed, accumulated suite time **1,624.765 s (27m 04.8s)**.

- **6,637 physical JDBC opens and 6,637 closes**; each one passed its encrypted-schema validation.
- **1,583,837 ms = 26m 23.8s** spent in the schema-guard instrumented region across those five tests — **97.48%** of their summed elapsed JUnit testcase time, as an *observed ratio*, not an independent causal estimate or CPU profile. Avoid assuming there is no overhead overlap.
- 34,187 logical statements, 28,580 issued on pinned transaction connections, 894 counted outer transaction begins. Physical-open count is far larger than expected for efficient reuse.
- Slowest methods: `encryptedPointPlacementsV8UpgradeRollbackReopenRacesAndDomainProof` **641.632 s**; `encryptedV5NamesRollbackAndConcurrentConnections` **513.483 s**; `encryptedMeasurementElementsV7MigrationRacesAndSharedContracts` **252.169 s**; `encryptedMigrationRollbackCrudAndReopen` **130.883 s**; `encryptedV3UpgradeAndV6Reopen` **86.598 s**.
- The JUnit `system-out` contains five independent metric lines, but their order need not correspond to each `testcase` ordering; totals above are accurate **for the full class**, not per-method attribution.
- Test purpose: prove native SQLCipher migration from legacy price and geometry schemas, genuine encrypted persistence, POINT/LINE/wall anchors, measurement elements, naming constraints, rollback and recovery, parallel connections, reopen, repository CRUD and integrity. These tests **must remain**; do not switch to plaintext, reduce KDF, or simply delete coverage to cut CI time.

This is a **proven dominant cost for this single class**. It does not explain all Windows CI duration: Desktop `MixedDetailsSceneTest` has its own expensive scene behavior and cache/runtime differences. SQLCipher's exact internal KDF/native cost breakdown has not been proven.

### Outcome of #148 and next actions

1. Keep only the already-tested safeguards in #148: opt-in in-memory metrics, proper native PreparedStatement closure, protected error/rollback tests, and the bounded child-JVM thread handoff investigation (30s handoff deadline, 90s child process deadline). The successful path is verified; a deliberately forced native deadlock has **not** been demonstrated.
2. **No production pool/reuse implemented.** The native JDBC `Connection` has mutable, unsynchronized proxy state; sequential cross-thread tests passing does **not** establish production pool safety. A safe production change requires bounded exclusive lease, transaction pinning, failure invalidation, close/drain and encrypted-file/security proofs, reviewed separately.
3. #134: route Android-only work without native Windows full-suite execution; shared/critical DB changes still get relevant Windows encrypted checks. Keep Windows full suite available as deliberate manual/periodic/Windows-release verification with transparent skipped coverage. Branch protection remains disabled by owner's decision.
4. Future #147 work: benchmark and potentially optimize SQLCipher open churn, inspect `PriceCatalogWindowsCipherTest` test-fixture repetition and run `MixedDetailsSceneTest` **uncached** in isolation, then compare uncached full-suite wall time. Preserve complete checks and real encryption. Issue #147 remains open; this is **not** a request to resume now.

**Documentation supersedes the earlier “verification still required” paragraph above for the code HEAD `639377ba` only.** Any subsequent commit, including this documentation update, requires a fresh CI status assessment before merging. No new Android/Windows/iOS host functionality is promised by this checkpoint.

### Follow-up fixture benchmark (#150)

[Draft PR #151](https://github.com/aleks-nl5/andrey-ceilings-android/pull/151) records a test-only change to legacy PriceCatalog fixture setup. On the [paired Windows run](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45024437830), the metrics-only baseline `725b7d40` and candidate `402cc29c` each passed all five PriceCatalog cases on the same runner, using `--rerun-tasks --no-build-cache`. Summed JUnit testcase time fell from 1,362.141 s to 837.224 s (38.5%). Physical opens, closes and successful schema guards each fell from 6,634 to 4,427 (33.3%). All nine focused controls passed.

The comparison measures fixture setup and leaves production JDBC behavior as it was in #148. The baseline ran first, so dependency and filesystem warmup can affect the command timings. Issue #147 remains open for production reuse research.
