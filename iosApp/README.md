# iOS integration status

The `:shared` module now declares `iosArm64` and `iosSimulatorArm64` targets and exports a static framework named `AndreyCeilingsShared`.

This is the framework foundation only; a runnable `iosApp.xcodeproj` has not yet been added. iOS builds require macOS and Xcode. The next migration step is to add the minimal SwiftUI host that calls the shared Compose entry point, then validate the framework on an iOS simulator before moving product screens.
