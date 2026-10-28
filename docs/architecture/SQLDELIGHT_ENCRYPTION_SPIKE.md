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
| iOS | Isolated SQLDelight Native driver proof using `io.github.yet300:sqlcipher-driver:0.1.0` | **Simulator proof passes in CI; partial** | Add encrypted-file-at-rest/header verification and prove wrong-key failure leaves the existing database unchanged; then test on a physical device and review exact dependency licenses/notices |
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
- The iOS CI workflow runs `:shared:iosSimulatorArm64Test`, including `SqlCipherIosProofTest`. The test checks a non-empty `PRAGMA cipher_version`, data persistence across close/reopen, and wrong-key failure. The successful CI run is evidence that this isolated simulator proof passed; it does **not** yet verify the database file's encrypted-at-rest header or that a wrong-key attempt leaves file bytes unchanged.

## License review — 2026-10-28

### iOS candidate: `io.github.yet300:sqlcipher-driver:0.1.0`

- The driver repository declares **Apache-2.0** for its own code.
- The bundled SQLCipher code is under Zetetic's **BSD-style Community Edition license**. Before distribution, preserve the required copyright/license text and third-party notices, including relevant SQLite/crypto-provider notices, in a user-accessible place such as **Settings → About → Open Source Licenses**. Review the exact published artifact's `NOTICE` and transitive dependencies before release.
- The official SQLCipher Community Edition terms permit use in open- and closed-source software subject to the license/notice conditions; this review did not identify a requirement to buy a commercial SQLCipher license solely for this Community Edition use. This is a preliminary engineering license review, not legal advice.
- The driver is an early-stage third-party project. Its README says Android and iOS database file formats intentionally diverge; do not assume encrypted database-file portability.
- Apple link configuration needs special care: prevent linking plain system SQLite alongside the bundled SQLCipher exporter (the driver's README calls out SQLDelight's `linkSqlite.set(false)`) and verify the final linked binary and runtime cipher probe.

### Follow-up verification — Windows Community Edition build and version compatibility

- Zetetic's own support forum confirms that it does **not** provide Windows Community Edition build instructions or public support for Windows compilation; vendor-supported Windows packages are the commercial offerings. A Community Edition build remains technically possible, but would be our own maintained build and release responsibility. Do not treat the existence of an upstream SQLCipher source tree or a third-party Windows build as vendor-supported packaging. Reference: https://discuss.zetetic.net/t/sqlcipher-build-on-windows/4576/2
- The public `toxicity-io/sqlite-mc` release list currently tops out at `2.1.0-2.2.3-0` (2025-08-19), pairing SQLDelight 2.1.0 with SQLite3MultipleCiphers 2.2.3. The repository's current development version is `2.1.0-2.2.3-1-SNAPSHOT`; it still targets SQLDelight 2.1.0, not this project's SQLDelight 2.4.1. No published compatible release was found in this check. References: https://github.com/toxicity-io/sqlite-mc/releases and https://github.com/toxicity-io/sqlite-mc/blob/master/gradle.properties
- Its README's recommended integration applies the `sqlite-mc` Gradle plugin and says to remove the existing SQLDelight plugin/driver dependencies. Applying that recipe directly would therefore change the current shared module's SQLDelight toolchain; it is not a safe drop-in dependency-only change.
- **Decision for this iteration:** do not add `sqlite-mc` to the app or shared module yet, and do not downgrade SQLDelight globally just to test it. The next technical step is a disposable proof-of-concept isolated from the app: either (a) verify a separately versioned driver/API that can coexist with SQLDelight 2.4.1, or (b) use a dedicated Windows/JVM test module/branch to evaluate the plugin's required version changes and native packaging. Keep the production dependency graph unchanged until that proof passes.
- Official SQLCipher Community Edition on Windows remains an alternative only if we are willing to own and maintain the Windows native build/JNI/JDBC integration. The official JDBC driver is the supported turnkey route, but is Enterprise/commercial.

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

Keep the current Android prototype isolated. For Windows, first resolve the SQLDelight 2.4.1 compatibility blocker for sqlite-mc or explicitly evaluate the cost of maintaining a custom SQLCipher Community Edition native build. In parallel, run an iOS simulator proof. Use throwaway databases and random test keys. The test must assert a cipher-specific probe (where supported), encrypted file contents, persistence after close/reopen, and rejection of a wrong key. Do not connect either driver to app repositories or replace Room based on dependency documentation alone.

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
2. Extend the existing iOS simulator proof with encrypted-file-at-rest and unchanged-file-after-wrong-key assertions; complete the Windows SQLDelight 2.4.1 JDBC integration proof. Keep both on isolated test databases and test-only keys.
3. Add CI jobs and negative-key/encrypted-file checks for each target.
4. Decide whether encrypted database files must be portable between Android, iOS and Windows. If yes, prove compatible SQLCipher settings and file format end-to-end; otherwise define a platform-independent backup/export format.
5. Only after all gates pass, design the shared schema and repository adapters for clients, projects/measurements, price items, estimates and estimate lines.
6. Integrate production key management and then plan the explicit test-data reset / Room replacement.

## References

- SQLDelight overview and supported SQLite targets: https://sqldelight.github.io/sqldelight/
- SQLDelight JVM SQLite driver: https://sqldelight.github.io/sqldelight/jvm_sqlite/
- Zetetic SQLCipher documentation (including Java/JDBC and Apple integrations): https://www.zetetic.net/sqlcipher/documentation/
- Community KMP SQLCipher driver (research lead only; review its format warning and license before any adoption): https://github.com/yet300/SQLCipher


### Additional Windows/JVM candidate — SQLCipherMultiplatform (reviewed 2026-10-28)

- Repository: https://github.com/s0d3s/SQLCipherMultiplatform
- The README describes a KMP Android/JVM API, a JNI-backed JDBC driver, and native runtime payloads including Windows x64. It also includes a SQLDelight sample, so it is a promising isolated proof-of-concept candidate for the existing Desktop/JVM target.
- The README explicitly says **iOS is not implemented**. This candidate can only address Windows/JVM; it does not solve the iOS database driver.
- **Compatibility is not established:** the repository's current build declares Kotlin Multiplatform 2.3.20 and SQLDelight plugin 2.0.2, while this project currently uses Kotlin 2.4.21 and SQLDelight 2.4.1. Do not add it to `:shared` or alter the app dependency graph until an isolated sample proves the API and toolchain can coexist without downgrading the project's versions.
- **License needs clarification before redistribution:** the repository README says Apache-2.0, while its published Maven metadata surfaced during research lists MIT for the artifact. It bundles upstream SQLCipher under Zetetic's BSD-style Community Edition terms. Verify the exact artifact POM, LICENSE/NOTICE files, transitive notices and redistribution obligations before deciding; do not infer the effective license from the README badge alone.
- The library advertises a `jdbc:sqlcipher:` driver and SQLDelight integration, but these claims still require runtime proof in our Windows CI: cipher-specific probe, encrypted-at-rest check, CRUD, close/reopen persistence, wrong-key rejection without plaintext fallback, and native DLL packaging from a clean runner.
- References: https://github.com/s0d3s/SQLCipherMultiplatform ; https://central.sonatype.com/artifact/io.github.s0d3s/sqlcipher-multiplatform ; https://www.zetetic.net/sqlcipher/license/ .

**Decision:** keep this as the leading open-source Windows/JVM proof-of-concept candidate, not an approved production dependency. Next, evaluate it in a disposable sample isolated from `:shared` and the production dependency graph. Compare it with the official Zetetic JDBC route (Enterprise/commercial) before selecting an implementation.

### Windows/JVM follow-up — published SQLCipherMultiplatform 0.2.13 (2026-10-28)

A fresh review of the upstream README and Maven Central metadata found a published `io.github.s0d3s:sqlcipher-multiplatform:0.2.13` artifact. The README documents a `SqlCipherDatabaseFactory` API, a custom `jdbc:sqlcipher:` driver, and native payloads for Windows x64. This makes it a more concrete candidate for an isolated Windows proof than the earlier repository-only review.

- Upstream README: https://github.com/s0d3s/SQLCipherMultiplatform
- Published artifact and POM metadata: https://central.sonatype.com/artifact/io.github.s0d3s/sqlcipher-multiplatform
- The published POM identifies Kotlin stdlib 2.3.20 and reports MIT metadata, while the upstream README describes the project as Apache-2.0 and its bundled SQLCipher sources as BSD-style. Resolve the exact license/NOTICE obligations for the specific published artifact and bundled native payloads before redistribution.
- The upstream README lists Java 17 and Kotlin 2.3.x as its toolchain baseline. This project currently uses Kotlin 2.4.21 and SQLDelight 2.4.1, so compatibility with our build is **not established**. Do not add this artifact to `:shared` yet.
- The upstream README explicitly says iOS is not implemented; this candidate can only be evaluated for Windows/JVM and does not replace the separate iOS proof.
- The upstream project documents sample checks for CRUD, encrypted-at-rest content, and wrong-key rejection. Our proof must independently run on a clean Windows x64 GitHub Actions runner and additionally verify close/reopen persistence, fail-closed wrong-key behavior, and that a wrong key cannot create or overwrite a plaintext database.

**Next action:** create/run a disposable Windows/JVM proof that tests the published artifact independently of the app dependency graph, then separately test whether the JDBC driver can back SQLDelight 2.4.1 without changing the shared module's Kotlin/SQLDelight versions. Until both checks pass, keep the candidate research-only.

### Windows/JVM proof of concept added — 2026-10-28

- Added a disposable Gradle project at `tools/sqlcipher-windows-poc`. It is deliberately not included in the root Gradle settings and does not alter `:shared`, `:desktopApp`, Android, Room, Go/AAR, or production repositories.
- The standalone proof pins SQLCipherMultiplatform `0.2.13` and Kotlin Gradle plugin `2.3.20`, matching the dependency's published Kotlin baseline rather than changing the app's Kotlin `2.4.21` toolchain.
- The proof checks a SQLCipher-specific `PRAGMA cipher_version`, CRUD, absence of the plaintext SQLite file header, persistence after close/reopen, rejection of a wrong key, and unchanged database bytes after the wrong-key attempt. It uses a throwaway database and a generated test-only key.
- The Windows CI workflow now runs `gradle --no-daemon -p tools/sqlcipher-windows-poc verifySqlCipher` on a clean Windows runner.
- **Status: passed on Windows x64 CI (2026-10-28).** The isolated `verifySqlCipher` step passed on the clean Windows runner, confirming the published library's direct API can initialize, report `PRAGMA cipher_version`, perform CRUD, keep data encrypted at rest, persist across close/reopen, reject a wrong key, and leave the database bytes unchanged after the wrong-key attempt. This is evidence for the isolated direct-API POC only; it does not establish SQLDelight JDBC-driver integration or production readiness.
- Do not treat this as an approved production dependency until CI passes, SQLDelight compatibility is verified, and the artifact/native payload licensing discrepancy is resolved.


### Windows/JVM proof result — 2026-10-28

- CI run for CMP/KMP platforms: **success**; Windows Desktop and SQLCipher POC passed.
- Android APK CI run: **success**; Android build, tests, emulator SQLCipher instrumentation, and Debug/Release APK steps passed.
- The POC is still isolated under `tools/sqlcipher-windows-poc`; it is not included in the root Gradle settings and does not replace Room or alter production database code.
- Verified on Windows/JVM: cipher version probe, encrypted-file header check, CRUD, close/reopen persistence, wrong-key rejection, and unchanged file digest after the wrong-key attempt.
- **Still open:** test the JDBC driver through SQLDelight **2.4.1** without changing the app's Kotlin **2.4.21** / SQLDelight **2.4.1** toolchain; resolve artifact/native payload licensing; and run an independent iOS simulator encryption proof.
- This result does not establish cross-platform encrypted-file portability. Keep the production dependency graph unchanged and do not replace Room until all acceptance gates pass.


### iOS simulator proof result — 2026-10-28

- The shared module contains `SqlCipherIosProofTest`, executed by the CMP/KMP workflow's `:shared:iosSimulatorArm64Test` step.
- The test checks that `PRAGMA cipher_version` returns a non-empty value, that a row survives close/reopen with the same test key, and that a wrong key fails.
- The latest successful CMP/KMP workflow included this iOS simulator test step.
- **Remaining iOS gaps:** no encrypted-file header/content assertion and no file-digest check proving a wrong-key attempt leaves the database unchanged; no physical-device validation. The proof uses synthetic keys and disposable database names only.
- Therefore iOS is no longer “untested,” but its proof remains partial and is not production approval.


### SQLDelight 2.4.1 + Windows SQLCipher JDBC integration POC — added 2026-10-28

- Added a second standalone Gradle project at `tools/sqlcipher-sqldelight-jdbc-poc`. It is intentionally not included in root Gradle settings and uses Kotlin Gradle plugin **2.4.21**, SQLDelight JDBC driver/runtime **2.4.1**, and SQLCipherMultiplatform JDBC core + Windows x64 native artifact **0.2.13**.
- Added a Windows CI step `verifySqlCipherSqlDelight` to exercise the SQLDelight `JdbcSqliteDriver` with the candidate `jdbc:sqlcipher:` driver.
- The disposable test checks `PRAGMA cipher_version`, table create/insert/read, the non-plaintext SQLite header, close/reopen persistence, wrong-key rejection, and unchanged database SHA-256 after the wrong-key attempt.
- This is specifically designed to test the candidate driver with the project's current Kotlin/SQLDelight versions without changing the app or `:shared` dependency graph.
- **Status: awaiting CI result.** The test code and workflow step are committed, but compatibility is not confirmed until a clean Windows x64 runner completes the new step successfully. If it fails, keep the candidate isolated and use the logs to identify API/toolchain/native-packaging incompatibility.
- License note remains open: published Maven metadata identifies MIT, while upstream README and bundled SQLCipher materials describe different license terms. Verify exact artifact and native payload notices before any production adoption.
