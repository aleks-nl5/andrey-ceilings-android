# iOS host integration status

The long-term target is a runnable iOS host that embeds the shared Compose Multiplatform UI and delegates platform-specific behavior (storage, files, PDF, backup, and operating-system APIs) through explicit adapters.

This repository is starting the CMP/KMP migration from the current Android `main`; this README documents the intended iOS work and does not claim that an iOS framework or runnable Xcode project already exists on this branch.

## Planned work

- [ ] Choose and verify a stable Kotlin/Compose Multiplatform/AGP/Gradle version set against official compatibility guidance.
- [ ] Add the shared KMP module with `iosArm64` and `iosSimulatorArm64` targets.
- [ ] Export a framework for a SwiftUI host and add a runnable `iosApp.xcodeproj`.
- [ ] Add macOS CI to compile the iOS simulator framework.
- [ ] Verify the host launches on an iOS simulator before migrating real product screens.
- [ ] Add platform adapters for encrypted local storage, file selection, PDF, and backup/restore only after their contracts are defined.

## Constraints

- Android remains the primary platform and behavioral/UI reference during the migration.
- Do not replace the current Android app with a placeholder scaffold.
- Preserve the encrypted database requirements, offline-first operation, and existing user data.
- Keep Go/AAR until shared Kotlin calculation parity and regression tests pass.
- Device synchronization is not part of this phase.

An iOS framework build requires macOS and Xcode; successful JVM/Android compilation alone does not verify iOS integration.
