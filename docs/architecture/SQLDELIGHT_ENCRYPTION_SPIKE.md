# SQLDelight encryption feasibility spike

**Status (2026-10-28):** Android emulator encryption test passes in CI. SQLDelight is still a candidate, not yet approved as the production database. Do not connect this prototype to production repositories or replace Room until the platform gates below are satisfied.

## Current verified state

- SQLDelight 2.4.1 generates the prototype schema and type-safe client CRUD API.
- JVM in-memory CRUD tests exercise generated queries only; they do not test encryption.
- The Android SQLCipher instrumentation test checks that the database file does not have the plaintext SQLite header, data survives close/reopen with the correct key, and the wrong key fails to open/read the database.
- The test uses an isolated prototype file and a random test key. It does not migrate or alter the current Room database and does not verify the production key manager.
- CI runs `:app:connectedDebugAndroidTest` on an Android emulator. Successful CI proves the test path on that emulator, not on a physical device or other platforms.

## Platform feasibility — not yet a production decision

| Target | Candidate path | Current status | Required proof |
|---|---|---|---|
| Android | SQLDelight Android driver + Zetetic SQLCipher Android | **Prototype test passes in CI** | Physical-device test; integrate the real app key manager; test backup/restore and failure/recovery behavior |
| iOS | SQLDelight Native driver linked with SQLCipher | **Not integrated or tested** | Build/link a native test; assert SQLCipher is actually loaded; encrypted-file, reopen, wrong-key and persistence tests on simulator and device |
| Windows desktop (JVM) | SQLDelight JDBC driver backed by an encrypted SQLite/SQLCipher implementation | **No verified implementation selected** | Confirm licensing, Windows x64 packaging, key API, native library loading, encryption probe, reopen and wrong-key tests |
| Cross-platform backup/restore | Depends on cipher version, key derivation, database format and key management | **Not established** | Explicitly test restore between supported platforms, or document that encrypted database backups are platform-specific and provide a safe logical export/import format |

SQLDelight supports SQLite across Android, JVM and Kotlin/Native, but SQLDelight itself does not encrypt SQLite files. Each platform's driver and linked SQLite implementation must be verified independently.

A community SQLCipher KMP driver was found that describes Android and Apple targets, but its README explicitly states that Android and iOS database file formats intentionally diverge. It therefore must **not** be adopted as a cross-platform backup format without further analysis and an explicit portability decision. It is only a research lead, not an approved dependency.

The official Zetetic documentation lists a SQLCipher-enabled JDBC driver for Java under its commercial/enterprise offerings. Evaluate licensing and packaging before considering it for Windows. Do not assume that the standard SQLDelight SQLite JDBC driver is encrypted.

## Research update — 2026-10-28

### Windows/JVM

- Zetetic's official SQLCipher JDBC driver supports Windows x86/x64 and interoperable SQLCipher databases, but the vendor lists it as an **Enterprise Edition** feature. This is a valid commercial option, not a free/open-source assumption. Before selecting it, obtain licensing/pricing details and verify the intended distribution model. Reference: https://www.zetetic.net/sqlcipher/jdbc/
- Open-source candidate: `toxicity-io/sqlite-mc`, an SQLDelight driver built on SQLite3MultipleCiphers, published under Apache-2.0 and documenting Windows x86/x64 binaries. It supports a SQLCipher-compatible encryption mode and describes SQLCipher-v4 migration configuration. This may avoid an Enterprise-only JDBC dependency, but is a different SQLite/cipher implementation and is **not approved yet**. It must be tested for actual encryption, wrong-key fail-closed behavior, native packaging, license notices, and compatibility with the project's SQLDelight/Kotlin/Gradle versions. References: https://github.com/toxicity-io/sqlite-mc and https://github.com/sqlcipher/sqlcipher.
- The existing Windows workflow proves that the current desktop host and JVM tests compile/run on Windows; it does not yet load any encrypted database driver.

### iOS

- Research candidate: `io.github.yet300:sqlcipher-driver:0.1.0`, an Apache-2.0 SQLDelight KMP driver that bundles SQLCipher for Apple targets. Its README explicitly says Android and iOS file formats intentionally diverge, so it must **not** be treated as a portable cross-platform database-file format. It is an early-stage third-party dependency and needs independent review and an iOS simulator encryption test before adoption. Reference: https://github.com/yet300/SQLCipher.
- The current successful iOS CI job only links the shared framework. It does not prove SQLCipher is linked or that an encrypted database can be opened on iOS.

## License review — 2026-10-28

### iOS candidate: `io.github.yet300:sqlcipher-driver:0.1.0`

- The driver repository declares **Apache-2.0** for its own code.
- The bundled SQLCipher code is under Zetetic's **BSD-style Community Edition license**. Before distribution, preserve the required copyright/license text and third-party notices, including relevant SQLite/crypto-provider notices, in a user-accessible place such as **Settings → About → Open Source Licenses**. Review the exact published artifact's `NOTICE` and transitive dependencies before release.
- The official SQLCipher Community Edition terms permit use in open- and closed-source software subject to the license/notice conditions; this review did not identify a requirement to buy a commercial SQLCipher license solely for this Community Edition use. This is a preliminary engineering license review, not legal advice.
- The driver is an early-stage third-party project. Its README says Android and iOS database file formats intentionally diverge; do not assume encrypted database-file portability.
- Apple link configuration needs special care: prevent linking plain system SQLite alongside the bundled SQLCipher exporter (the driver's README calls out SQLDelight's `linkSqlite.set(false)`) and verify the final linked binary and runtime cipher probe.

### Windows candidate: `toxicity-io/sqlite-mc`

- The project declares **Apache-2.0** for its own driver/plugin. It uses SQLite3MultipleCiphers, not Zetetic's SQLCipher implementation, so “SQLCipher-compatible” does not mean byte-for-byte or behaviorally identical in every setting.
- Its published plugin version encodes the SQLDelight and SQLite3MultipleCiphers versions. The candidate version documented in the README is tied to SQLDelight 2.1.x-era tooling, while this project currently uses SQLDelight 2.4.1. Do not apply its plugin or remove the existing SQLDelight plugin until a compatible release/toolchain is verified.
- The official Zetetic SQLCipher JDBC route is listed as an Enterprise feature; treat it as a commercial fallback that requires a vendor quote/license review.

References:
- Driver and bundled notices: https://github.com/yet300/SQLCipher
- Official SQLCipher license terms: https://www.zetetic.net/sqlcipher/license/
- Official SQLCipher Community Edition overview: https://www.zetetic.net/sqlcipher/community/
- Windows candidate and versioning: https://github.com/toxicity-io/sqlite-mc
- Commercial JDBC option: https://www.zetetic.net/sqlcipher/jdbc/

### Current recommendation

Keep the current Android prototype isolated. First run a minimal, test-only proof for the most promising open-source Windows option and an iOS simulator proof, each with its own throwaway database and random test key. The test must assert a cipher-specific probe (where supported), encrypted file contents, persistence after close/reopen, and rejection of a wrong key. Do not connect either driver to app repositories or replace Room based on dependency documentation alone.

## Acceptance gates before replacing Room

1. **Key management:** use the existing application key-management flow (or a reviewed platform-specific equivalent); never hard-code, log, commit, or silently regenerate a production key. No plaintext fallback.
2. **Encryption is real:** verify an encrypted header and run a SQLCipher-specific probe such as checking that `PRAGMA cipher_version` returns a non-empty value where supported. A filename or successful CRUD alone is insufficient.
3. **Negative test:** a wrong key must fail closed; it must never create a fresh plaintext database or overwrite the existing encrypted file.
4. **Lifecycle:** close/reopen persistence, transaction behavior, schema initialization, corruption handling and backup/restore are tested.
5. **Platform proof:** automated tests run on Android emulator, iOS simulator and Windows x64. Add physical-device checks where practical before release.
6. **Data and migration:** current database contains test data only, so a deliberate reset is acceptable for the test-stage Room → SQLDelight migration. The reset must be explicit and must not become production behavior.
7. **Go remains in place:** do not remove Go/AAR or migrate calculation consumers as part of this database spike. Calculation migration requires separate parity/regression tests.

## Recommended next sequence

1. Resolve the Windows/JVM encryption implementation and licensing question.
2. Build the smallest encrypted CRUD proof for iOS and Windows, using isolated test databases and test-only keys.
3. Add CI jobs and negative-key/encrypted-file checks for each target.
4. Decide whether encrypted database files must be portable between Android, iOS and Windows. If yes, prove compatible SQLCipher settings and file format end-to-end; otherwise define a platform-independent backup/export format.
5. Only after all gates pass, design the shared schema and repository adapters for clients, projects/measurements, price items, estimates and estimate lines.
6. Integrate production key management and then plan the explicit test-data reset / Room replacement.

## References

- SQLDelight overview and supported SQLite targets: https://sqldelight.github.io/sqldelight/
- SQLDelight JVM SQLite driver: https://sqldelight.github.io/sqldelight/jvm_sqlite/
- Zetetic SQLCipher documentation (including Java/JDBC and Apple integrations): https://www.zetetic.net/sqlcipher/documentation/
- Community KMP SQLCipher driver (research lead only; review its format warning and license before any adoption): https://github.com/yet300/SQLCipher
