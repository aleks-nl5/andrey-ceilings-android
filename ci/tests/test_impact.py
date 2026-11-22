"""Per-platform, fail-closed source-set impact classification (#134)."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from impact import Change, classify, impact_from_git, parse_changes


def outcome(*paths):
    return classify([Change("M", p) for p in paths])


ANDROID_FONT = "app/src/main/res/font/inter_variable.ttf"
ANDROID_SCREENS = "app/src/main/java/com/andreyceilings/app/ui/screens"
SHARED_MEASUREMENT = "shared/src/commonMain/kotlin/com/andreyceilings/shared/measurement"
SHARED_DRAWING = f"{SHARED_MEASUREMENT}/MeasurementDrawing.kt"
SHARED_GRADIENT = "shared/src/commonMain/kotlin/com/andreyceilings/shared/theme/CeilingsEdgeGradient.kt"
WORKSPACE_LAYOUT_TEST = "com.andreyceilings.app.MeasurementWorkspaceLayoutTest"
OVERLAY_DRAG_TEST = "com.andreyceilings.app.OverlayDragTouchTest"
AREA_TOUCH_TEST = "com.andreyceilings.app.AreaFeatureTouchTest"


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

    def test_android_presentation_asset_selects_matching_android_smoke_only(self):
        self.assert_scope(ANDROID_FONT, "TARGETED", ("smoke", "skip", "skip"))
        self.assertEqual(outcome(ANDROID_FONT).android_smoke_tests, (WORKSPACE_LAYOUT_TEST,))

    def test_android_pdf_and_unmapped_ui_require_android_deep(self):
        for path in (
            f"{ANDROID_SCREENS}/ImmutableEstimatePdfSnapshot.kt",
            f"{ANDROID_SCREENS}/EstimateDocumentExport.kt",
            f"{ANDROID_SCREENS}/PriceListScreen.kt",
            "shared/src/androidMain/kotlin/com/andreyceilings/shared/ui/Ui.kt",
        ):
            self.assert_scope(path, "TARGETED", ("deep", "skip", "skip"))
            self.assertEqual(outcome(path).android_smoke_tests, ())

    def test_platform_lifecycle_and_non_ui_adapter_change_requires_native_deep(self):
        # MainActivity initializes SQLCipher despite living outside /data/db.
        # Unsafe path-only smoke would skip encrypted startup regression.
        for path, expected in (
            ("app/src/main/java/com/andreyceilings/app/MainActivity.kt",
             ("deep", "skip", "skip")),
            ("app/src/main/java/com/andreyceilings/app/BackupCoordinator.kt",
             ("deep", "skip", "skip")),
            ("shared/src/androidMain/kotlin/com/a/AndroidDriverFactory.kt",
             ("deep", "skip", "skip")),
            ("desktopApp/src/main/kotlin/com/a/Main.kt",
             ("skip", "deep", "skip")),
            ("shared/src/jvmMain/kotlin/com/a/WindowsLifecycle.kt",
             ("skip", "deep", "skip")),
            ("shared/src/iosMain/kotlin/com/a/IosLifecycle.kt",
             ("skip", "skip", "deep")),
        ):
            self.assert_scope(path, "TARGETED", expected)

    def test_windows_sqlcipher_and_test_changes_are_windows_deep_only(self):
        for path in (
            "shared/src/jvmMain/kotlin/com/andreyceilings/shared/db/SqlCipherWindowsDriverFactory.kt",
            "shared/src/jvmTest/kotlin/com/andreyceilings/shared/db/PriceCatalogWindowsCipherTest.kt",
            "desktopApp/src/test/kotlin/com/andreyceilings/desktop/MixedDetailsSceneTest.kt",
        ):
            self.assert_scope(path, "TARGETED", ("skip", "deep", "skip"))

    def test_individual_platforms_and_android_security(self):
        self.assert_scope("shared/src/iosMain/kotlin/com/a/ui/IosScreen.kt", "TARGETED",
                          ("skip", "skip", "smoke"))
        self.assert_scope("shared/src/iosMain/kotlin/com/a/db/IosCipher.kt", "TARGETED",
                          ("skip", "skip", "deep"))
        self.assert_scope("app/src/androidTest/java/com/a/OverlayDragTouchTest.kt", "TARGETED",
                          ("deep", "skip", "skip"))
        self.assert_scope("app/src/main/AndroidManifest.xml", "TARGETED",
                          ("deep", "skip", "skip"))

    def test_shared_ui_consumers_are_all_targeted_without_expensive_native_db(self):
        for path, tests in (
            (SHARED_DRAWING,
             (AREA_TOUCH_TEST, WORKSPACE_LAYOUT_TEST, OVERLAY_DRAG_TEST)),
            (SHARED_GRADIENT, (WORKSPACE_LAYOUT_TEST,)),
        ):
            self.assert_scope(path, "TARGETED", ("smoke", "smoke", "smoke"))
            self.assertEqual(outcome(path).android_smoke_tests, tests)

    def test_android_smoke_unions_mapped_classes_and_deep_overrides_them(self):
        result = outcome(ANDROID_FONT, SHARED_DRAWING, SHARED_GRADIENT)
        self.assertEqual(result.android_smoke_tests, (AREA_TOUCH_TEST, WORKSPACE_LAYOUT_TEST, OVERLAY_DRAG_TEST))
        result = outcome(ANDROID_FONT, SHARED_DRAWING, f"{ANDROID_SCREENS}/EstimateDocumentExport.kt")
        self.assertEqual(result.depths, ("deep", "smoke", "smoke"))
        self.assertEqual(result.android_smoke_tests, ())

    def test_shared_business_data_database_and_config_fail_closed_full(self):
        for path in (
            "shared/src/commonMain/kotlin/com/a/livepricing/IndividualEstimatePricing.kt",
            "shared/src/commonMain/kotlin/com/a/measurement/MeasurementEditModel.kt",
            "shared/src/commonMain/kotlin/com/a/ui/UnreviewedScreen.kt",
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/navigation/AppNavigationState.kt",
            f"{SHARED_MEASUREMENT}/MeasurementDetailsScreen.kt",
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
            ANDROID_FONT,
            "shared/src/jvmMain/kotlin/com/a/db/SqlCipherWindowsDriver.kt",
            "shared/src/iosMain/kotlin/com/a/ui/IosUi.kt",
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
