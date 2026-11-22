"""Per-platform, fail-closed source-set impact classification (#134)."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from impact import Change, classify, impact_from_git, parse_changes


def outcome(*paths):
    return classify([Change("M", p) for p in paths])


class ImpactTest(unittest.TestCase):
    def assert_scope(self, path, mode, depths):
        with self.subTest(path=path):
            result = outcome(path)
            self.assertEqual(result.mode, mode)
            self.assertEqual(result.depths, depths)
            self.assertEqual(result.targets, tuple(
                name for name, level in zip(("android", "desktop", "ios"), depths)
                if level != "skip"))

    def test_documentation_only_fast(self):
        result = outcome("README.md", "docs/architecture/strategy.md")
        self.assertEqual((result.mode, result.depths), ("FAST", ("skip", "skip", "skip")))

    def test_android_ui_is_not_a_windows_native_regression(self):
        for path in (
            "app/src/main/java/com/andreyceilings/app/ui/Screen.kt",
            "shared/src/androidMain/kotlin/com/andreyceilings/shared/ui/Ui.kt",
        ):
            self.assert_scope(path, "TARGETED", ("smoke", "skip", "skip"))

    def test_windows_sqlcipher_and_test_changes_are_windows_deep_only(self):
        for path in (
            "shared/src/jvmMain/kotlin/com/andreyceilings/shared/db/SqlCipherWindowsDriverFactory.kt",
            "shared/src/jvmTest/kotlin/com/andreyceilings/shared/db/PriceCatalogWindowsCipherTest.kt",
            "desktopApp/src/test/kotlin/com/andreyceilings/desktop/MixedDetailsSceneTest.kt",
        ):
            self.assert_scope(path, "TARGETED", ("skip", "deep", "skip"))

    def test_individual_platforms_and_android_security(self):
        self.assert_scope("shared/src/iosMain/kotlin/com/a/IosAdapter.kt", "TARGETED",
                          ("skip", "skip", "smoke"))
        self.assert_scope("shared/src/iosMain/kotlin/com/a/db/IosCipher.kt", "TARGETED",
                          ("skip", "skip", "deep"))
        self.assert_scope("app/src/androidTest/java/com/a/OverlayDragTouchTest.kt", "TARGETED",
                          ("deep", "skip", "skip"))
        self.assert_scope("app/src/main/AndroidManifest.xml", "TARGETED",
                          ("deep", "skip", "skip"))

    def test_shared_ui_consumers_are_all_targeted_without_expensive_native_db(self):
        for path in (
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/theme/CeilingsTheme.kt",
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/navigation/AppNavigationState.kt",
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/measurement/MeasurementEditScreen.kt",
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/measurement/MeasurementDrawing.kt",
        ):
            self.assert_scope(path, "TARGETED", ("smoke", "smoke", "smoke"))

    def test_shared_business_data_database_and_config_fail_closed_full(self):
        for path in (
            "shared/src/commonMain/kotlin/com/a/livepricing/IndividualEstimatePricing.kt",
            "shared/src/commonMain/kotlin/com/a/measurement/MeasurementEditModel.kt",
            "shared/src/commonMain/kotlin/com/a/db/EncryptedRepository.kt",
            "shared/src/commonMain/sqldelight/com/a/db/BusinessSchema.sq",
            "shared/src/commonTest/kotlin/com/a/EstimateTest.kt",
            "shared/build.gradle.kts", "gradle.properties", "settings.gradle.kts",
            "ci/impact.py", ".github/workflows/cmp-kmp-platforms.yml",
            "AGENTS.md", "assets/mystery.dat",
            "iosApp/iosApp.xcodeproj/project.pbxproj",
            "app/src/main/assets/unknown-native.bin",
        ):
            self.assert_scope(path, "FULL", ("deep", "deep", "deep"))

    def test_mixed_platform_levels_union_never_downgrades(self):
        result = outcome(
            "app/src/main/java/com/a/Screen.kt",
            "shared/src/jvmMain/kotlin/com/a/db/SqlCipherWindowsDriver.kt",
            "shared/src/iosMain/kotlin/com/a/IosUi.kt",
            "docs/info.md",
        )
        self.assertEqual(result.mode, "TARGETED")
        self.assertEqual(result.depths, ("smoke", "deep", "smoke"))

        result = outcome("app/src/main/java/com/a/Screen.kt",
                         "app/src/androidTest/java/com/a/ScreenTest.kt")
        self.assertEqual(result.depths, ("deep", "skip", "skip"))

        result = outcome("app/src/main/java/com/a/Screen.kt",
                         "shared/src/commonMain/kotlin/com/a/db/Migration.kt")
        self.assertEqual((result.mode, result.depths), ("FULL", ("deep", "deep", "deep")))

    def test_unsafe_diff_status_or_missing_history_full(self):
        self.assertEqual(classify([]).mode, "FULL")
        for status in ("D", "R100", "C75", "T", "U"):
            with self.subTest(status=status):
                result = classify([Change(status, "app/src/main/java/Screen.kt")])
                self.assertEqual(result.depths, ("deep", "deep", "deep"))
        self.assertEqual(impact_from_git("", "").mode, "FULL")
        self.assertEqual(impact_from_git("x" * 40, "0" * 40).mode, "FULL")

    def test_nul_parsing_and_rename_are_unambiguous(self):
        self.assertEqual(parse_changes(b"M\0docs/spec a.md\0A\0app/src/main/java/X.kt\0"), [
            Change("M", "docs/spec a.md"), Change("A", "app/src/main/java/X.kt")])
        self.assertEqual(parse_changes(b"R100\0old.md\0new.md\0"),
                         [Change("R100", "new.md")])
        for malformed in (b"M\0a", b"R100\0old\0", b"?\0a\0", b"M\0bad\nname\0"):
            with self.subTest(malformed=malformed):
                with self.assertRaises(ValueError):
                    parse_changes(malformed)


if __name__ == "__main__":
    unittest.main()
