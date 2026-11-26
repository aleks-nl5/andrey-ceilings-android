# F0 — підготовчий аудит Firebase / прототип
26.11.2026 · #174 · audited main `a5fe0d7f358c8a345a21d09fbc19cd2b9c60ddc2`. Це статичний аудит, не runtime evidence. Власник погодив №22–30, обрав стандартний SDK; production не починати. #143A PR #178 не змінювати.

## Підтверджено в репозиторії
- `app/build.gradle.kts`: applicationId com.andreyceilings.app, minSdk 30, target/compileSdk 37, WorkManager 2.12.0, PKCS12 env signing. `versionCode = GITHUB_RUN_ID / 1000` може колізіювати; fallback 600000 + workflow run number. F2 потрібен спільний serialized allocator з monotonic floor вище всіх виданих APK, bounded Android limit, retry identity, no downgrade. Ключ/відбиток фактичного APK ще не перевірено.
- `.github/workflows/pr-sign-release.yml`: trusted main signer, PR artifact data only, apksigner verify. `android-debug-apk.yml` і `cmp-kmp-platforms.yml`: FULL push/main + manual. На audited SHA FULL runs 45779446002 / 45779446144 cancelled; не stable gate evidence. F2 перевіряти workflow identity/exact SHA/success, не перепризначати PR smoke як FULL.
- Public development repo; private archive окремо, не створений. Public PR APK можуть бути доступні незалежно від Firebase. Cross-repo token scoped to private releases, no secrets in PR runner; OIDC feasibility окремо.
- `PortableBackupManager.createVerifiedSnapshot`: consistent encrypted snapshot + verification у cacheDir. F4 потрібна durable private storage, separate retention, durable journal, no dependency on user backup password/folder. `SqlCipherRestoreManager`, `DatabaseKeyManager`, `StorageSpaceGuard` — кандидати для reuse; не доведено recovery після upgrade. Не змінювати звичайні backup worker/retention.
- Main manifest INTERNET, no REQUEST_INSTALL_PACKAGES in inspected source; merged manifest/permission/installer behavior to verify. Brand label still СтеляПро; branding separate.

## Bounded prototype proposal (окремий дозвіл)
Ізольований disposable Android app із власним package/signing key та synthetic records, 2 test APK versions, Spark project без billing; no production app dependency/CI changes, no real customer data, no actual stable archive creation. Standard tester SDK only, current official version pinned after recheck. Owner tests physical Android; targeted automated state tests, no broad UI tests.

| ID | Proof | Expected |
|---|---|---|
| P01 | tester invite/sign-in/revoke, unauth/offline | app remains usable; check/download denied when not authorized; document latency and already downloaded limits |
| P02 | latest release metadata/changelog, two releases during update | verify exact selected release or flag blocker |
| P03 | manual SDK update, progress, mobile warning, Wi-Fi switch | no background update; no fake cancel; document actual network behavior |
| P04 | download failure, installer cancel, denied unknown-source permission, retry | terminal evidence only; no duplicate updates; manual retry fresh snapshot |
| P05 | background/process death/reboot during backup/download/install and late installer outcome | durable transaction state; no writes while installer may still succeed; conservative recovery path without permanent lock; unresolved outcome is blocker |
| P06 | synthetic writer concurrency + backup failure override/cancel | all writer paths quiescent before snapshot; explicit override; no stale snapshot |
| P07 | v1→v2 migration healthy/fails, startup fails | copy retained on unknown/failure; safe recovery proof, no destructive automatic downgrade; delete only healthy verified terminal case |
| P08 | SDK APK cleanup and partial download | document supported behavior; return to owner if retention unacceptable |
| P09 | release expiry / same APK redistribution, testers/group quota, pricing, developer verification | recheck official limits and verify expiry semantics; no billing; reminder 15d before expiry after first stable release |
| P10 | Crashlytics consent default off, opt-in/revoke, unsent data | document actual SDK transmission/retention, redaction, no real sensitive data; production F5 separate |

Sources to recheck at execution: [SDK](https://firebase.google.com/docs/app-distribution/set-up-alerts), [Android SDK reference](https://firebase.google.com/docs/reference/android/com/google/firebase/appdistribution/package-summary), [pricing](https://firebase.google.com/pricing), [tester FAQ](https://firebase.google.com/docs/app-distribution/troubleshooting), [Crashlytics](https://firebase.google.com/docs/crashlytics/customize-crash-reports?platform=android), [Android verification](https://developer.android.com/developer-verification). No unverified version/limit asserted as runtime proof.

## Exit gate
Evidence table with actual device/SDK/API/versions, success/failure observations and reproducible steps; owner acceptance for any remaining compromises. F0 remains open until prototype and decisions verified. No production integration before core roadmap and authorization. No merge by agent.
