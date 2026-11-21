"""Conservative, table-driven coverage for the Draft PR impact advisory."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from impact import Change, classify, impact_from_git, parse_changes


class ImpactTest(unittest.TestCase):
    def test_documentation_only_is_fast(self):
        result = classify([Change("M", "README.md"), Change("A", "docs/roadmap.md")])
        self.assertEqual((result.mode, result.targets), ("FAST", ()))

    def test_one_platform_and_union(self):
        for path, target in (
            ("app/src/main/java/com/app/MainActivity.kt", "android"),
            ("shared/src/androidMain/kotlin/core/Foo.kt", "android"),
            ("desktopApp/src/main/kotlin/foo/Window.kt", "desktop"),
            ("shared/src/jvmMain/kotlin/core/JvmAdapter.kt", "desktop"),
            ("shared/src/iosMain/kotlin/core/IOSAdapter.kt", "ios"),
        ):
            with self.subTest(path=path):
                v = classify([Change("M", path)])
                self.assertEqual((v.mode, v.targets), ("TARGETED", (target,)))
        mixed = classify([
            Change("M", "app/src/main/java/com/app/MainActivity.kt"),
            Change("M", "desktopApp/src/main/kotlin/foo/Window.kt"),
            Change("M", "shared/src/iosMain/kotlin/core/IOSAdapter.kt"),
            Change("M", "docs/screen.md"),
        ])
        self.assertEqual((mixed.mode, mixed.targets), ("TARGETED", ("android", "desktop", "ios")))

    def test_critical_and_cross_platform_fallback_to_full(self):
        paths = [
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/measurement/MeasurementDrawing.kt",
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/estimate/FrozenEstimateDocument.kt",
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/prices/PriceCatalog.kt",
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/calculation/KmpCeilingCalculator.kt",
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/theme/CeilingsTheme.kt",
            "shared/src/commonMain/kotlin/com/andreyceilings/shared/navigation/AppNavigation.kt",
            "shared/src/androidMain/kotlin/com/andreyceilings/shared/db/EncryptedPrototypeDriver.android.kt",
            "shared/src/iosMain/kotlin/com/andreyceilings/shared/db/EncryptedPrototypeDriver.ios.kt",
            "app/src/androidTest/java/com/andreyceilings/app/QuoteHostRestorationTest.kt",
            "shared/src/jvmTest/kotlin/FixtureTest.kt",
            "desktopApp/src/test/kotlin/ComposeTest.kt",
            "app/src/main/AndroidManifest.xml",
            "shared/src/commonMain/sqldelight/database/Foo.sq",
            "shared/build.gradle.kts", "gradle.properties", "settings.gradle.kts",
            "ci/impact.py", ".github/workflows/cmp-kmp-platforms.yml", "AGENTS.md",
            "assets/mystery.dat",
        ]
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(classify([Change("M", path)]).mode, "FULL")

    def test_unrecognized_status_or_absent_data_cannot_look_green(self):
        self.assertEqual(classify([]).mode, "FULL")
        for status in ("D", "R100", "C75", "T", "U"):
            with self.subTest(status=status):
                self.assertEqual(classify([Change(status, "README.md")]).mode, "FULL")
        self.assertEqual(impact_from_git("", "").mode, "FULL")
        self.assertEqual(impact_from_git("x" * 40, "0" * 40).mode, "FULL")

    def test_git_nul_names_and_rename_are_parsed_without_reinterpretation(self):
        self.assertEqual(parse_changes(b"M\0docs/spec a.md\0A\0app/src/main/java/X.kt\0"), [
            Change("M", "docs/spec a.md"), Change("A", "app/src/main/java/X.kt")])
        self.assertEqual(parse_changes(b"R100\0old.md\0new.md\0"), [Change("R100", "new.md")])
        for malformed in (b"M\0a", b"R100\0old\0", b"?\0a\0", b"M\0bad\nname\0"):
            with self.subTest(malformed=malformed):
                with self.assertRaises(ValueError):
                    parse_changes(malformed)

    def test_docs_plus_platform_does_not_miss_platform(self):
        result = classify([Change("A", "docs/ui.md"), Change("M", "app/src/main/java/X.kt")])
        self.assertEqual((result.mode, result.targets), ("TARGETED", ("android",)))


if __name__ == "__main__":
    unittest.main()
